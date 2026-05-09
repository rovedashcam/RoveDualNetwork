package com.rove.dualnetwork.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rove.dualnetwork.api.DashcamApiService
import com.rove.dualnetwork.api.InternetApiService
import com.rove.dualnetwork.network.DualNetworkManager
import com.rove.dualnetwork.network.NetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val networkManager = DualNetworkManager(app)

    val networkState: StateFlow<NetworkState> = networkManager.state

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        networkManager.start()
        networkManager.state.onEach { net ->
            _ui.update { it.copy(networkLabel = net.label) }
        }.launchIn(viewModelScope)
    }

    // ── Dashcam ───────────────────────────────────────────────────────────────

    /** GET ?custom=1&cmd=1008&par=1  →  Switch dashcam to Live Video mode */
    fun showLiveVideo() = dashcamCommand(par = 1, label = "Live Video")

    /** GET ?custom=1&cmd=1008&par=2  →  Switch dashcam to Playback / recorded video mode */
    fun showDashcamVideo() = dashcamCommand(par = 2, label = "Dashcam Video")

    /** GET ?custom=1&cmd=1008&par=0  →  Stop / exit current mode */
    fun stopStream() = dashcamCommand(par = 0, label = "Stop")

    private fun dashcamCommand(par: Int, label: String) {
        val svc = networkManager.dashcamRetrofit?.create(DashcamApiService::class.java)
            ?: return setDashcamResult("Not connected to dashcam WiFi")

        viewModelScope.launch {
            _ui.update { it.copy(dashcamLoading = true, dashcamResult = "") }
            runCatching {
                svc.sendCommand(custom = 1, cmd = 1008, par = par)
            }.onSuccess { response ->
                val body = response.body()?.string()?.trim() ?: "(empty body)"
                setDashcamResult(
                    "[$label]  HTTP ${response.code()} ${response.message()}\n\n$body"
                )
            }.onFailure { e ->
                setDashcamResult("[$label] Error: ${e.message}")
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
        networkManager.stop()
    }
}

data class UiState(
    val networkLabel:   String  = "Initializing…",
    val dashcamLoading: Boolean = false,
    val internetLoading:Boolean = false,
    val dashcamResult:  String  = "",
    val internetResult: String  = ""
)
