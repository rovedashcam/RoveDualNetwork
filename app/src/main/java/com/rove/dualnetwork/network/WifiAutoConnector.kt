package com.rove.dualnetwork.network

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Connects this device to any in-range WiFi whose SSID starts with
 * "ROVE_R2-4K-DUAL-PRO_" using passphrase "12345678".
 *
 * Strategy (Android 10+):
 *  1. Already on a ROVE_* SSID at the system level → done, no UI.
 *  2. Scan to find the exact in-range SSID (e.g. ROVE_R2-4K-DUAL-PRO_79c138).
 *  3. Save it as a [WifiNetworkSuggestion] so the OS auto-joins it whenever
 *     it's in range (silent after a one-time approval notification).
 *  4. Force-connect immediately via [WifiNetworkSpecifier]. The OS shows a
 *     "Connect to device" picker with the exact SSID listed; user taps
 *     Connect once. On Android 12+ this connection is *concurrent* — the
 *     phone keeps home/cellular wifi for internet AND adds the dashcam wifi
 *     for this app's traffic.
 *
 * The first launch shows the picker dialog once. After Android caches the
 * approval, future launches connect silently.
 */
class WifiAutoConnector(private val context: Context) {

    companion object {
        private const val TAG = "WifiAutoConnector"
        const val SSID_PREFIX = "ROVE_R2-4K-DUAL-PRO_"
        const val PASSPHRASE  = "12345678"
    }

    enum class State { Idle, Connecting, Connected, Failed, Unsupported, NoPermission, WifiOff }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Volatile private var requestCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var scanReceiver: BroadcastReceiver? = null

    fun connect(
        onResult: (State, message: String) -> Unit,
        onNetworkAvailable: (Network) -> Unit = {},
        onNetworkLost: () -> Unit = {}
    ): State {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onResult(State.Unsupported,
                "Auto-connect requires Android 10+. Use Open WiFi Settings to join " +
                "${SSID_PREFIX}… manually.")
            return State.Unsupported
        }
        if (!hasNearbyWifiPermission()) {
            onResult(State.NoPermission, "Location / Nearby-devices permission missing.")
            return State.NoPermission
        }
        if (!wifiManager.isWifiEnabled) {
            onResult(State.WifiOff, "Please turn WiFi on, then tap Retry.")
            return State.WifiOff
        }

        // 1) Already on a ROVE_* SSID? Done.
        currentSystemRoveSsid()?.let { ssid ->
            Log.i(TAG, "Already connected to $ssid — no action")
            onResult(State.Connected, "Connected to $ssid")
            return State.Connected
        }

        if (requestCallback != null) return State.Connecting

        onResult(State.Connecting, "Searching for dashcam WiFi…")

        // 2) Find exact SSID via scan.
        scanForRoveSsid { ssid ->
            if (ssid == null) {
                Log.w(TAG, "No ROVE_* SSID found in scan")
                onResult(
                    State.Failed,
                    "Dashcam WiFi not in range. Power on the dashcam, " +
                    "then tap Retry — or use Open WiFi Settings."
                )
                return@scanForRoveSsid
            }

            // 3) Save as suggestion (silent auto-join for future launches).
            saveAsSuggestion(ssid)

            // 4) Force connect now (system dialog appears the first time).
            forceConnect(ssid, onResult, onNetworkAvailable, onNetworkLost)
        }
        return State.Connecting
    }

    fun openWifiSettings() {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun release() {
        requestCallback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        requestCallback = null
        scanReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        scanReceiver = null
    }

    fun hasNearbyWifiPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 33) {
            val nearby = ContextCompat.checkSelfPermission(
                context, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            return fine && nearby
        }
        return fine
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** SSID of the system-default wifi network IF it starts with our prefix. */
    private fun currentSystemRoveSsid(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val info = caps.transportInfo as? WifiInfo ?: continue
                val ssid = info.ssid?.removeSurrounding("\"") ?: continue
                if (ssid == "<unknown ssid>" || ssid == WifiManager.UNKNOWN_SSID) continue
                if (ssid.startsWith(SSID_PREFIX)) return ssid
            }
        }
        @Suppress("DEPRECATION")
        val raw = wifiManager.connectionInfo?.ssid?.removeSurrounding("\"") ?: return null
        if (raw == "<unknown ssid>" || raw == WifiManager.UNKNOWN_SSID) return null
        return if (raw.startsWith(SSID_PREFIX)) raw else null
    }

    private fun scanForRoveSsid(callback: (String?) -> Unit) {
        cachedRoveSsid()?.let { callback(it); return }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                try { context.unregisterReceiver(this) } catch (_: Exception) {}
                if (scanReceiver === this) scanReceiver = null
                callback(cachedRoveSsid())
            }
        }
        scanReceiver = receiver
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        @Suppress("DEPRECATION")
        val started = wifiManager.startScan()
        if (!started) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            scanReceiver = null
            callback(null)
        }
    }

    private fun cachedRoveSsid(): String? = try {
        wifiManager.scanResults
            .firstOrNull { it.SSID?.startsWith(SSID_PREFIX) == true }
            ?.SSID
    } catch (_: SecurityException) { null }

    private fun saveAsSuggestion(ssid: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(PASSPHRASE)
                .setIsAppInteractionRequired(false)
                .build()
            wifiManager.removeNetworkSuggestions(listOf(suggestion))
            val status = wifiManager.addNetworkSuggestions(listOf(suggestion))
            Log.i(TAG, "addNetworkSuggestions for $ssid → status=$status")
        } catch (e: Exception) {
            Log.w(TAG, "saveAsSuggestion failed: ${e.message}")
        }
    }

    private fun forceConnect(
        ssid: String,
        onResult: (State, String) -> Unit,
        onNetworkAvailable: (Network) -> Unit,
        onNetworkLost: () -> Unit
    ) {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(PASSPHRASE)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Force-connect succeeded: $network ($ssid)")
                // The Network granted by a Specifier is reserved for THIS
                // request — DualNetworkManager's generic wifi callback will
                // not see it, so we hand it over directly here.
                onNetworkAvailable(network)
                onResult(State.Connected, "Connected to $ssid")
            }
            override fun onUnavailable() {
                Log.w(TAG, "Force-connect: user cancelled or no match")
                requestCallback = null
                onResult(
                    State.Failed,
                    "Could not connect to $ssid. Tap Connect on the system " +
                    "dialog when it appears, or use Open WiFi Settings."
                )
            }
            override fun onLost(network: Network) {
                // Intentionally NOT calling onNetworkLost() here. A specifier-
                // granted Network drops transiently during wifi roaming /
                // scoring and that would flip the dashcam UI to "Disconnected"
                // even though the phone is still on ROVE_* at the system level
                // (and the dashcam Retrofit still works through the cached
                // socketFactory until it's actually torn down). The adopted
                // network is released cleanly via release() / onCleared().
                Log.w(TAG, "Specifier WiFi lost: $network — keeping adoption sticky")
            }
        }
        requestCallback = cb
        Log.i(TAG, "Force-connecting to $ssid via specifier")
        cm.requestNetwork(request, cb)
        onResult(
            State.Connecting,
            "Tap \"Connect\" on the system dialog when it appears " +
            "(SSID: $ssid)."
        )
    }
}
