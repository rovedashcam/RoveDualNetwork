package com.rove.dualnetwork.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.InetAddress
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rove.dualnetwork.api.DashcamApiService
import com.rove.dualnetwork.api.InternetApiService
import com.rove.dualnetwork.network.CameraModel
import com.rove.dualnetwork.network.DashcamConfig
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
     * Returns the live SSID. Tries `NetworkCapabilities.transportInfo` first
     * (modern API 31+), falls back to deprecated `WifiManager.connectionInfo`
     * — the latter is what's actually working on Xiaomi/MIUI even though
     * Path 1 returns redacted info.
     */
    private fun liveSsid(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val info = caps.transportInfo as? WifiInfo
                val ssid = info?.ssid?.removeSurrounding("\"")
                if (!ssid.isNullOrBlank() &&
                    ssid != WifiManager.UNKNOWN_SSID &&
                    ssid != "<unknown ssid>") {
                    Log.d("WifiSSID", "liveSsid via TransportInfo net=$net → $ssid")
                    return ssid
                }
            }
        }
        @Suppress("DEPRECATION")
        val raw = wifiManager.connectionInfo?.ssid
        if (raw == null || raw == WifiManager.UNKNOWN_SSID || raw == "<unknown ssid>") {
            return null
        }
        val ssid = raw.removeSurrounding("\"").takeIf { it.isNotBlank() }
        Log.d("WifiSSID", "liveSsid via WifiManager → $ssid")
        return ssid
    }

    /**
     * Finds the wifi Network whose LinkProperties have a directly-connected
     * route to [DashcamConfig.host]. This is the Network we want to bind
     * Retrofit on — picking any wifi Network would adopt the wrong one when
     * the device has both a stale leftover and the live ROVE network in
     * `cm.allNetworks`.
     */
    private fun findDashcamNetwork(): Network? {
        val dashcamAddr = try { InetAddress.getByName(DashcamConfig.host) }
                          catch (_: Exception) { return null }
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val lp = cm.getLinkProperties(net) ?: continue
            val hasRoute = lp.routes.any { route ->
                !route.isDefaultRoute &&
                runCatching { route.destination.contains(dashcamAddr) }.getOrDefault(false)
            }
            if (hasRoute) {
                Log.d("WifiSSID", "findDashcamNetwork → $net (route to ${DashcamConfig.host})")
                return net
            }
        }
        Log.d("WifiSSID", "findDashcamNetwork: no wifi network has route to ${DashcamConfig.host}")
        return null
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
        //
        // Also self-heal: if we're on a SSID that matches the selected camera
        // model but DualNetworkManager hasn't adopted a Network yet (Retrofit
        // is null), hand the Network straight over to adoptDashcamNetwork.
        //
        // We deliberately bypass DualNetworkManager.isDashcamNetwork on this
        // path — TransportInfo just told us the SSID matches, that's a
        // stronger signal than any route check. This is the same shortcut the
        // WifiAutoConnector specifier path uses, and it's what makes
        // "manually connect via WiFi Settings, then come back to the app"
        // actually work.
        viewModelScope.launch {
            while (true) {
                val s = liveSsid()
                if (s != _ui.value.currentSsid) {
                    Log.d("WifiSSID", "Live SSID = $s")
                    _ui.update { it.copy(currentSsid = s) }
                }
                if (s?.let { DashcamConfig.matchesSsid(it) } == true &&
                    networkManager.dashcamRetrofit == null) {
                    val net = findDashcamNetwork()
                    if (net != null) {
                        Log.i("WifiSSID", "On $s — adopting routable Network $net")
                        networkManager.adoptDashcamNetwork(net)
                    } else {
                        Log.w("WifiSSID", "On $s but no Network with route to " +
                                          "${DashcamConfig.host} — cannot adopt")
                    }
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
    /**
     * Silent on-launch check. Updates UI state only; never invokes
     * [WifiAutoConnector.connect], so it cannot trigger the
     * "Allow suggested Wi-Fi networks?" or "Connect to device" system dialogs.
     */
    fun tryAutoConnectWifi() {
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    autoConnectState   = WifiAutoConnector.State.Connecting,
                    autoConnectMessage = "Checking dashcam WiFi…"
                )
            }
            delay(1200)
            val ssid = liveSsid()
            _ui.update { it.copy(currentSsid = ssid) }

            val alreadyOnRove =
                networkState.value.wifiReady ||
                ssid?.let { DashcamConfig.matchesSsid(it) } == true

            if (alreadyOnRove) {
                Log.i("WifiAutoConnect", "Already on $ssid — no popup needed")
                // Trigger adoption right now so the user doesn't have to
                // wait for the next 1-second poll to bind Retrofit.
                if (networkManager.dashcamRetrofit == null) {
                    val net = findDashcamNetwork()
                    if (net != null) {
                        Log.i("WifiAutoConnect", "Direct-adopting network $net")
                        networkManager.adoptDashcamNetwork(net)
                    }
                }
                _ui.update {
                    it.copy(
                        autoConnectState   = WifiAutoConnector.State.Connected,
                        autoConnectMessage = "Connected to ${ssid ?: "dashcam WiFi"}"
                    )
                }
            } else {
                _ui.update {
                    it.copy(
                        autoConnectState   = WifiAutoConnector.State.Idle,
                        autoConnectMessage =
                            "Not on dashcam WiFi. Tap \"Connect to dashcam\" to join " +
                            "${DashcamConfig.displayName}."
                    )
                }
            }
        }
    }

    /**
     * User-initiated connect. ONLY this path may trigger the system popups
     * ("Allow suggested Wi-Fi networks?" / "Connect to device"). Wired to
     * the "Connect to dashcam" button.
     */
    fun userConnectToDashcam() {
        viewModelScope.launch {
            val ssid = liveSsid()
            val alreadyOnRove =
                networkState.value.wifiReady ||
                ssid?.let { DashcamConfig.matchesSsid(it) } == true
            if (alreadyOnRove) {
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
     * Switch to a different camera model. Updates [DashcamConfig], drops any
     * adoption tied to the previous model, then re-checks the live SSID
     * against the new matcher and updates UI state.
     */
    fun selectCamera(model: CameraModel) {
        if (DashcamConfig.selected == model) return
        Log.i("CameraSelect", "Switching camera model: ${DashcamConfig.selected} → $model")
        DashcamConfig.selected = model
        networkManager.onCameraModelChanged()
        // Clear stale results from the previous model.
        _ui.update {
            it.copy(
                selectedCamera     = model,
                dashcamResult      = "",
                autoConnectState   = WifiAutoConnector.State.Idle,
                autoConnectMessage = "Camera switched to ${model.displayName}. " +
                                     "Connect to its WiFi to use the API."
            )
        }
        // Re-fire the silent status check immediately so the UI reflects
        // whether we're already on the new model's SSID.
        tryAutoConnectWifi()
    }

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
        val url = "${DashcamConfig.baseUrl}?custom=1&cmd=$cmd" +
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
    val currentSsid:        String? = null,
    val selectedCamera:     CameraModel = DashcamConfig.selected
)
