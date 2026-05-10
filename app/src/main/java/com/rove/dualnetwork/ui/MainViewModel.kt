package com.rove.dualnetwork.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rove.dualnetwork.api.DashcamApiService
import com.rove.dualnetwork.api.InternetApiService
import com.rove.dualnetwork.network.DualNetworkManager
import com.rove.dualnetwork.network.NetworkState
import com.rove.dualnetwork.network.WifiAutoConnector
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val networkManager = DualNetworkManager(app)
    private val wifiAutoConnector = WifiAutoConnector(app)
    private val wifiManager =
        app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val cm =
        app.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val networkState: StateFlow<NetworkState> = networkManager.state

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /**
     * Reads the live SSID. Tries every path Android exposes; logs each
     * attempt so we can see in Logcat exactly which one (if any) succeeds.
     *
     * Reading SSID requires ACCESS_FINE_LOCATION on every modern Android.
     * Without it, all paths return "<unknown ssid>".
     */
    private fun liveSsid(): String? {
        // Path 1 (API 31+): NetworkCapabilities.transportInfo — works for
        // both system-default and app-scoped wifi networks.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val info = caps.transportInfo as? WifiInfo
                val raw = info?.ssid
                Log.d("WifiSSID", "Path1 net=$net ssidRaw=$raw")
                val ssid = raw?.removeSurrounding("\"")
                if (!ssid.isNullOrBlank() &&
                    ssid != WifiManager.UNKNOWN_SSID &&
                    ssid != "<unknown ssid>") {
                    return ssid
                }
            }
        }

        // Path 2: deprecated WifiManager.connectionInfo — pre-12 / fallback.
        @Suppress("DEPRECATION")
        val raw = wifiManager.connectionInfo?.ssid
        Log.d("WifiSSID", "Path2 connectionInfo.ssid=$raw")
        if (raw == null || raw == WifiManager.UNKNOWN_SSID || raw == "<unknown ssid>") return null
        return raw.removeSurrounding("\"").takeIf { it.isNotBlank() }
    }

    private var liveVideoAutoFired = false

    init {
        networkManager.start()
        networkManager.state.onEach { net ->
            _ui.update { it.copy(networkLabel = net.label, currentSsid = liveSsid()) }

            // Auto-fire Live Video the first time the dashcam WiFi becomes ready.
            if (net.wifiReady && !liveVideoAutoFired) {
                liveVideoAutoFired = true
                Log.d("DashcamAPI", "Auto-launching Live Video on startup (SSID=${liveSsid()})")
                showLiveVideo()
            }
        }.launchIn(viewModelScope)

        // Poll the live SSID — TransportInfo can become readable a moment
        // after wifiReady flips, and the user explicitly wants to see it.
        // Also self-heal: if we're on a ROVE_* SSID but DualNetworkManager
        // hasn't adopted a Network yet (Retrofit is null), nudge it to scan
        // every visible network and adopt one. This handles the case where
        // Android auto-joined the dashcam (e.g. via a previously-saved
        // network) and our wifi callback didn't fire because of API timing.
        viewModelScope.launch {
            while (true) {
                val s = liveSsid()
                if (s != _ui.value.currentSsid) {
                    Log.d("WifiSSID", "Live SSID = $s")
                    _ui.update { it.copy(currentSsid = s) }
                }
                if (s?.startsWith(WifiAutoConnector.SSID_PREFIX) == true &&
                    networkManager.dashcamRetrofit == null) {
                    Log.i("WifiSSID", "On $s but no dashcam Retrofit — re-adopting")
                    networkManager.tryAdoptAlreadyConnectedDashcam()
                }
                delay(1000)
            }
        }
    }

    // ── WiFi auto-connect ─────────────────────────────────────────────────────

    /**
     * Connects to the dashcam WiFi automatically. Safe to call repeatedly.
     *
     * Flow:
     *  1. Brief settle period so [DualNetworkManager] can detect an already-
     *     connected dashcam WiFi (avoids flashing a useless picker dialog).
     *  2. [WifiAutoConnector] then checks the live system SSID, saves the
     *     network as a suggestion for future silent auto-join, and only as a
     *     last resort shows the system "Connect to device" picker.
     */
    fun tryAutoConnectWifi() {
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    autoConnectState   = WifiAutoConnector.State.Connecting,
                    autoConnectMessage = "Checking dashcam WiFi…"
                )
            }
            // Give the existing wifi callback ~1.2s to fire if we're already
            // on the dashcam WiFi (the common case after the very first launch).
            delay(1200)
            _ui.update { it.copy(currentSsid = liveSsid()) }

            // Skip-if-connected guard. We check BOTH our own state flow AND
            // the live system SSID — if either says we're on a ROVE_* network
            // we must NOT issue a WifiNetworkSpecifier, because doing so would
            // create a competing app-scoped network grant that the existing
            // DualNetworkManager would then bind Retrofit to, resulting in
            // sockets that have a wifi transport but a broken route to the
            // dashcam (which is what produced the "Failed to connect to
            // /192.168.1.253:80" error after the auto-connect ran).
            val ssid = liveSsid()
            val alreadyOnRove =
                networkState.value.wifiReady ||
                ssid?.startsWith(WifiAutoConnector.SSID_PREFIX) == true

            if (alreadyOnRove) {
                Log.i("WifiAutoConnect", "Skipping auto-connect — already on $ssid")
                _ui.update {
                    it.copy(
                        autoConnectState   = WifiAutoConnector.State.Connected,
                        autoConnectMessage = "Already connected to ${ssid ?: "dashcam WiFi"}"
                    )
                }
                return@launch
            }

            wifiAutoConnector.connect(
                onResult = { state, message ->
                    Log.i("WifiAutoConnect", "$state — $message")
                    _ui.update {
                        it.copy(
                            autoConnectState   = state,
                            autoConnectMessage = message,
                            currentSsid        = liveSsid()
                        )
                    }
                },
                onNetworkAvailable = { network ->
                    // Specifier-granted networks are reserved for the specifier
                    // request and don't reach DualNetworkManager's wifi callback,
                    // so we hand the Network over explicitly.
                    Log.i("WifiAutoConnect", "Adopting network into DualNetworkManager: $network")
                    networkManager.adoptDashcamNetwork(network)
                },
                onNetworkLost = {
                    networkManager.releaseAdoptedDashcamNetwork()
                }
            )
        }
    }

    fun hasWifiPermission(): Boolean = wifiAutoConnector.hasNearbyWifiPermission()

    fun openWifiSettings() = wifiAutoConnector.openWifiSettings()

    /**
     * Surface granular permission state so the UI can tell the user exactly
     * which permission is missing instead of a vague "permission not granted."
     */
    fun permissionStatus(): String {
        val app = getApplication<Application>()
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val nearby = if (Build.VERSION.SDK_INT >= 33)
            androidx.core.content.ContextCompat.checkSelfPermission(
                app, android.Manifest.permission.NEARBY_WIFI_DEVICES
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        else true
        return "FINE_LOCATION=$fine, NEARBY_WIFI_DEVICES=$nearby"
    }

    fun onWifiPermissionDenied() {
        _ui.update {
            it.copy(
                autoConnectState   = WifiAutoConnector.State.NoPermission,
                autoConnectMessage = "Permission denied — auto-connect needs the " +
                                     "Nearby devices (or Location) permission so the OS " +
                                     "can show matching dashcam networks."
            )
        }
    }

    // ── Dashcam ───────────────────────────────────────────────────────────────

    /** GET ?custom=1&cmd=1008&par=1  →  Switch dashcam to Live Video mode */
    fun showLiveVideo() = dashcamCommand(cmd = 1008, par = 1, label = "Live Video")

    /** GET ?custom=1&cmd=1008&par=2  →  Switch dashcam to Playback / recorded video mode */
    fun showDashcamVideo() = dashcamCommand(cmd = 1008, par = 2, label = "Dashcam Video")

    /** GET ?custom=1&cmd=6019&par=0  →  Open Settings menu on dashcam */
    fun showSettings() = dashcamCommand(cmd = 6019, par = 0, label = "Settings")

    /** GET ?custom=1&cmd=3022  (no par) */
    fun sendCmd3022() = dashcamCommand(cmd = 3022, par = null, label = "Cmd 3022")

    private fun dashcamCommand(cmd: Int, par: Int?, label: String) {
        val url = "http://192.168.1.253/?custom=1&cmd=$cmd" +
                  (if (par != null) "&par=$par" else "")
        val ssid = liveSsid() ?: "?"
        Log.d("DashcamAPI", "[$label] → $url  (SSID=$ssid)")

        val svc = networkManager.dashcamRetrofit?.create(DashcamApiService::class.java)
            ?: return setDashcamResult(
                "[$label] $url\nSSID: $ssid\n\nNot connected to dashcam WiFi"
            )

        viewModelScope.launch {
            _ui.update { it.copy(dashcamLoading = true, dashcamResult = "[$label] → $url") }

            // Retry once on a connect-style failure: the dashcam occasionally
            // RSTs the very first SYN after a fresh wifi association.
            val response = runCatching { svc.sendCommand(custom = 1, cmd = cmd, par = par) }
                .recoverCatching { firstError ->
                    Log.w("DashcamAPI", "[$label] first attempt failed: ${firstError.message} — retrying")
                    delay(250)
                    svc.sendCommand(custom = 1, cmd = cmd, par = par)
                }

            response.onSuccess { resp ->
                val body = resp.body()?.string()?.trim() ?: "(empty body)"
                setDashcamResult(
                    "[$label]  $url\nSSID: $ssid\n" +
                    "HTTP ${resp.code()} ${resp.message()}\n\n$body"
                )
            }.onFailure { e ->
                Log.w("DashcamAPI", "[$label] failed: ${e.message}")
                setDashcamResult("[$label] $url\nSSID: $ssid\nError: ${e.message}")
            }
            _ui.update { it.copy(dashcamLoading = false) }
        }
    }

    // ── Internet ──────────────────────────────────────────────────────────────

    /**
     * Fetches https://www.google.com via the cellular socket (never touches
     * the dashcam WiFi) and shows the HTTP status + a snippet of the page.
     */
    fun fetchGoogle() {
        val svc = networkManager.internetRetrofit?.create(InternetApiService::class.java)
            ?: return setInternetResult("No cellular connection")

        viewModelScope.launch {
            _ui.update { it.copy(internetLoading = true, internetResult = "") }
            runCatching {
                svc.fetchGoogle()
            }.onSuccess { response ->
                val raw   = response.body()?.string() ?: ""
                // Extract <title> tag if present, otherwise show first 200 chars
                val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
                    .find(raw)?.groupValues?.get(1)?.trim() ?: ""
                val snippet = raw.take(200).replace(Regex("\\s+"), " ").trim()
                setInternetResult(
                    "HTTP ${response.code()} ${response.message()}\n" +
                    (if (title.isNotEmpty()) "Title: $title\n" else "") +
                    "\n$snippet…"
                )
            }.onFailure { e ->
                setInternetResult("Error: ${e.message}")
            }
            _ui.update { it.copy(internetLoading = false) }
        }
    }

    // ── VPN ───────────────────────────────────────────────────────────────────

    fun onVpnPermissionGranted() {
        networkManager.vpnPermissionGranted = true
        if (networkManager.state.value.wifiReady) {
            networkManager.stop()
            networkManager.start()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun setDashcamResult(msg: String)  = _ui.update { it.copy(dashcamResult  = msg) }
    private fun setInternetResult(msg: String) = _ui.update { it.copy(internetResult = msg) }

    override fun onCleared() {
        super.onCleared()
        wifiAutoConnector.release()
        networkManager.stop()
    }
}

data class UiState(
    val networkLabel:       String  = "Initializing…",
    val dashcamLoading:     Boolean = false,
    val internetLoading:    Boolean = false,
    val dashcamResult:      String  = "",
    val internetResult:     String  = "",
    val autoConnectState:   WifiAutoConnector.State = WifiAutoConnector.State.Idle,
    val autoConnectMessage: String  = "",
    val currentSsid:        String? = null
)
