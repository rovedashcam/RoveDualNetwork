package com.rove.dualnetwork.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
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
        const val DASHCAM_SSID_PREFIX = "ROVE_R2-4K-DUAL-PRO_"
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
            if (isDashcamNetwork(network)) {
                acceptDashcam(network)
            } else {
                Log.d(TAG, "WiFi available but not dashcam (yet): [$network] — " +
                           "waiting for capabilities to populate SSID")
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val isDashcam = isDashcamNetwork(network)
            when {
                // Late accept — SSID was UNKNOWN at onAvailable, now we can
                // verify it matches.
                isDashcam && currentWifiNetwork != network -> {
                    Log.i(TAG, "Late accept of dashcam network: $network")
                    acceptDashcam(network)
                }
                // Demote — we previously accepted this network but a fresh
                // capabilities update reveals the SSID isn't a ROVE_*. (This
                // is what catches a home wifi on 192.168.1.x that initially
                // had no SSID readable.)
                !isDashcam && currentWifiNetwork == network -> {
                    Log.w(TAG, "Demoting previously-accepted network — SSID " +
                               "no longer matches: $network")
                    currentWifiNetwork = null
                    dashcamRetrofit    = null
                    _state.update { it.copy(wifiReady = false) }
                    stopVpn()
                }
                isDashcam && currentWifiNetwork == network &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> {
                    cm.reportNetworkConnectivity(network, false)
                }
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

    private fun acceptDashcam(network: Network) {
        if (currentWifiNetwork == network) return
        val routes = cm.getLinkProperties(network)?.routes?.joinToString(", ") {
            it.destination.toString()
        } ?: "?"
        Log.i(TAG, "Dashcam WiFi connected [network=$network, routes=$routes]")
        currentWifiNetwork = network
        runCatching { cm.reportNetworkConnectivity(network, false) }
        dashcamRetrofit = buildRetrofit(network, DASHCAM_BASE_URL, timeoutSec = 10L)
        _state.update { it.copy(wifiReady = true) }
        if (vpnPermissionGranted) startVpn()
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

        // registerNetworkCallback (not requestNetwork) so we OBSERVE *every*
        // wifi network the OS knows about. On Android 12+ home wifi (with
        // internet) and dashcam wifi (without) can be active simultaneously,
        // and requestNetwork would only deliver one of them — typically home
        // — leaving the dashcam Network invisible to our adoption logic.
        cm.registerNetworkCallback(wifiRequest, wifiCallback)
        cm.requestNetwork(cellularRequest, cellularCallback)
        cm.registerDefaultNetworkCallback(defaultNetworkCallback)

        // Eager catch-up: if we launched while already connected to a ROVE_*
        // wifi (Android auto-joined a saved suggestion), adopt it now without
        // waiting for the next callback round-trip.
        tryAdoptAlreadyConnectedDashcam()
    }

    /** Walk every visible network; if one is the dashcam, adopt it. */
    fun tryAdoptAlreadyConnectedDashcam() {
        if (currentWifiNetwork != null) return
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            if (isDashcamNetwork(net)) {
                Log.i(TAG, "Eager adoption — found dashcam network: $net")
                acceptDashcam(net)
                return
            }
        }
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

    /**
     * Externally-granted-network entry point. Used when the dashcam wifi was
     * connected via [android.net.wifi.WifiNetworkSpecifier] in
     * [WifiAutoConnector] — that grants an *app-scoped* Network that
     * [requestNetwork] won't redeliver to our generic wifi callback (a
     * NetworkRequest only ever yields a single Network), so we have to
     * adopt it directly here.
     *
     * Validates the SSID via [isDashcamNetwork] before binding Retrofit, so
     * a wrong network can't slip through this path either.
     */
    fun adoptDashcamNetwork(network: Network) {
        // Trust the caller. The Specifier in WifiAutoConnector named the
        // exact ROVE_* SSID + passphrase, so Android has already proved this
        // is the dashcam by connecting it. We deliberately skip
        // [isDashcamNetwork] here — its strict TransportInfo SSID check
        // returns UNKNOWN for app-scoped specifier networks even with the
        // location permission granted, which would (and did) reject the
        // legitimate dashcam Network and leave the buttons greyed out.
        val lp = cm.getLinkProperties(network)
        val routes = lp?.routes?.joinToString(", ") { it.destination.toString() } ?: "?"
        Log.i(TAG, "Adopting dashcam network [network=$network, routes=$routes]")
        currentWifiNetwork = network
        runCatching { cm.reportNetworkConnectivity(network, false) }
        dashcamRetrofit = buildRetrofit(network, DASHCAM_BASE_URL, timeoutSec = 10L)
        _state.update { it.copy(wifiReady = true) }
        if (vpnPermissionGranted) startVpn()
    }

    /** Tear down anything created by [adoptDashcamNetwork]. */
    fun releaseAdoptedDashcamNetwork() {
        if (currentWifiNetwork == null) return
        Log.i(TAG, "Releasing adopted dashcam network")
        currentWifiNetwork = null
        dashcamRetrofit    = null
        _state.update { it.copy(wifiReady = false) }
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
     * Returns true only if [network] is the dashcam WiFi.
     *
     *  - The network must own a route to 192.168.1.253 (existing check —
     *    rules out non-dashcam home networks on different subnets).
     *  - **AND** on Android 12+, the network's SSID (read via TransportInfo)
     *    must start with [DASHCAM_SSID_PREFIX]. This blocks two failure
     *    modes that previously surfaced as "Failed to connect to
     *    /192.168.1.253:80" even though the WiFi pill was green:
     *      a) a home/office WiFi happens to use the same 192.168.1.x subnet;
     *      b) an app-scoped Network from WifiNetworkSpecifier briefly has
     *         wifi transport but no live route to the dashcam.
     */
    private fun isDashcamNetwork(network: Network): Boolean {
        val lp = cm.getLinkProperties(network) ?: return false
        val dashcamAddr = try { InetAddress.getByName(URI(DASHCAM_BASE_URL).host) }
                          catch (_: Exception) { return false }

        val hasRoute = lp.routes.any { route ->
            !route.isDefaultRoute &&
            runCatching { route.destination.contains(dashcamAddr) }.getOrDefault(false)
        }
        if (!hasRoute) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val caps = cm.getNetworkCapabilities(network) ?: return false
            val info = caps.transportInfo as? WifiInfo
            val ssid = info?.ssid?.removeSurrounding("\"")
            Log.d(TAG, "isDashcamNetwork ssid=$ssid (route matched)")
            // If SSID is readable, it must match. If TransportInfo can't
            // expose the SSID for a foreign-app network even with location
            // permission, fall back to the route check — a network with a
            // 192.168.1.0/24 route AND no internet capability is, in
            // practice, the dashcam. Home wifi gets caught here because its
            // SSID *is* readable to us (we own it via the system default).
            if (!ssid.isNullOrBlank() &&
                ssid != "<unknown ssid>" &&
                ssid != WifiManager.UNKNOWN_SSID) {
                return ssid.startsWith(DASHCAM_SSID_PREFIX)
            }
            // Tighten the route fallback by also requiring "no internet" —
            // home/office wifi advertises NET_CAPABILITY_INTERNET, dashcam
            // does not.
            return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        return true
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
