package com.rove.dualnetwork.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Manages two simultaneous network paths:
 *
 *   WiFi     → Dashcam device  (local LAN, no internet)
 *   Cellular → Internet        (kept alive while WiFi is active)
 *
 * Internet for ALL apps on the phone is guaranteed by DualNetworkVpnService,
 * which intercepts other apps' traffic and routes it through cellular.
 *
 * This class:
 *  1. Detects when the dashcam WiFi connects (identified by its subnet).
 *  2. Starts the VPN so other apps keep internet access.
 *  3. Keeps cellular alive via requestNetwork even when WiFi is connected.
 *  4. Binds this app's process to cellular so our own internet calls work.
 *  5. Keeps reportNetworkConnectivity(false) as an additional hint to Android.
 */
class DualNetworkManager(private val context: Context) {

    companion object {
        private const val TAG = "DualNetworkManager"

        const val DASHCAM_BASE_URL  = "http://192.168.1.253/"
        const val INTERNET_BASE_URL = "https://www.google.com/"
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow(NetworkState())
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    @Volatile var dashcamRetrofit:  Retrofit? = null; private set
    @Volatile var internetRetrofit: Retrofit? = null; private set

    @Volatile private var currentWifiNetwork:     Network? = null
    @Volatile private var currentCellularNetwork: Network? = null

    /** Set to true by MainActivity once the user has approved the VPN. */
    @Volatile var vpnPermissionGranted: Boolean = false

    // ── WiFi callback ─────────────────────────────────────────────────────────
    // requestNetwork (not registerNetworkCallback) is required so that
    // reportNetworkConnectivity() is honoured on Android 12+.

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!isDashcamNetwork(network)) return
            Log.i(TAG, "Dashcam WiFi connected [$network]")
            currentWifiNetwork = network

            // Hint to Android's network scorer: this WiFi has no internet.
            // Combined with the VPN this covers all Android versions.
            cm.reportNetworkConnectivity(network, false)

            dashcamRetrofit = buildRetrofit(network, DASHCAM_BASE_URL, timeoutSec = 10L)
            _state.update { it.copy(wifiReady = true) }

            // Start VPN so other apps route through cellular, not this WiFi.
            if (vpnPermissionGranted) startVpn()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!isDashcamNetwork(network)) return
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                // Android re-validated the dashcam WiFi as having internet.
                // Push back immediately.
                cm.reportNetworkConnectivity(network, false)
            }
        }

        override fun onLost(network: Network) {
            if (currentWifiNetwork != network) return
            Log.i(TAG, "Dashcam WiFi disconnected")
            currentWifiNetwork = null
            dashcamRetrofit    = null
            _state.update { it.copy(wifiReady = false) }
            stopVpn()
        }

        override fun onUnavailable() = Unit
    }

    // ── Cellular callback ─────────────────────────────────────────────────────

    private val cellularCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Cellular available [$network]")
            currentCellularNetwork = network
            // Make cellular the default for this app's process so our own
            // network calls (Retrofit, Coil, etc.) use cellular, not WiFi.
            cm.bindProcessToNetwork(network)
            // Keep the VPN's cellular reference up-to-date.
            DualNetworkVpnService.cellularNetwork = network
            internetRetrofit = buildRetrofit(network, INTERNET_BASE_URL, timeoutSec = 30L)
            _state.update { it.copy(cellularReady = true) }
        }

        override fun onLost(network: Network) {
            if (currentCellularNetwork != network) return
            Log.i(TAG, "Cellular lost")
            currentCellularNetwork = null
            DualNetworkVpnService.cellularNetwork = null
            cm.bindProcessToNetwork(null)
            internetRetrofit = null
            _state.update { it.copy(cellularReady = false) }
        }

        override fun onUnavailable() {
            Log.w(TAG, "Cellular unavailable — no SIM or mobile data disabled")
            _state.update { it.copy(cellularReady = false) }
        }
    }

    // ── Default-network watchdog ──────────────────────────────────────────────
    // Belt-and-suspenders: if WiFi somehow becomes the system default, push back.

    private val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = cm.getNetworkCapabilities(network) ?: return
            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                         !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            if (isWifi) {
                currentWifiNetwork?.let { cm.reportNetworkConnectivity(it, false) }
                currentCellularNetwork?.let { cm.bindProcessToNetwork(it) }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val cellularRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.requestNetwork(wifiRequest, wifiCallback)
        cm.requestNetwork(cellularRequest, cellularCallback)
        cm.registerDefaultNetworkCallback(defaultNetworkCallback)
    }

    fun stop() {
        safeUnregister(wifiCallback)
        safeUnregister(cellularCallback)
        safeUnregister(defaultNetworkCallback)
        cm.bindProcessToNetwork(null)
        dashcamRetrofit        = null
        internetRetrofit       = null
        currentWifiNetwork     = null
        currentCellularNetwork = null
        DualNetworkVpnService.cellularNetwork = null
        _state.value = NetworkState()
        stopVpn()
    }

    // ── VPN control ───────────────────────────────────────────────────────────

    private fun startVpn() {
        DualNetworkVpnService.cellularNetwork = currentCellularNetwork
        context.startForegroundService(Intent(context, DualNetworkVpnService::class.java))
        Log.i(TAG, "VPN start requested")
    }

    private fun stopVpn() {
        context.startService(
            Intent(context, DualNetworkVpnService::class.java)
                .setAction(DualNetworkVpnService.ACTION_STOP)
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns true only if [network] is the dashcam WiFi by checking that the
     * dashcam's IP address falls within the network's directly-connected routes.
     * This prevents accidentally treating home WiFi (a different subnet) as the
     * dashcam network.
     */
    private fun isDashcamNetwork(network: Network): Boolean {
        val lp = cm.getLinkProperties(network) ?: return false
        val dashcamAddr = try { InetAddress.getByName(URI(DASHCAM_BASE_URL).host) }
                          catch (_: Exception) { return false }
        return lp.routes.any { route ->
            !route.isDefaultRoute &&
            runCatching { route.destination.contains(dashcamAddr) }.getOrDefault(false)
        }
    }

    private fun buildRetrofit(network: Network, baseUrl: String, timeoutSec: Long): Retrofit {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
        val client = OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            // Resolve DNS through the correct network to prevent lookup leaks.
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
            })
            .addInterceptor(logging)
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private fun safeUnregister(cb: ConnectivityManager.NetworkCallback) {
        try { cm.unregisterNetworkCallback(cb) } catch (_: IllegalArgumentException) {}
    }
}

data class NetworkState(
    val wifiReady: Boolean = false,
    val cellularReady: Boolean = false
) {
    val bothReady get() = wifiReady && cellularReady
    val label: String get() = when {
        bothReady     -> "Dual-network active"
        wifiReady     -> "WiFi only (no internet)"
        cellularReady -> "Cellular only (no dashcam)"
        else          -> "No networks"
    }
}
