package com.rove.dualnetwork.ui

import android.Manifest
import android.app.Activity
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rove.dualnetwork.network.NetworkState
import com.rove.dualnetwork.network.WifiAutoConnector
import com.rove.dualnetwork.ui.theme.GreenOnline
import com.rove.dualnetwork.ui.theme.RedOffline
import com.rove.dualnetwork.ui.theme.RoveDualNetworkTheme
import com.rove.dualnetwork.ui.theme.RoveOrange

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onVpnPermissionGranted()
        }
    }

    private val wifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                          // On Android 13+ NEARBY_WIFI_DEVICES alone enables scanning,
                          // but without FINE the SSID still reads as "<unknown ssid>".
                          results[Manifest.permission.NEARBY_WIFI_DEVICES] == true
        if (fineGranted) {
            viewModel.tryAutoConnectWifi()
        } else {
            viewModel.onWifiPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request VPN permission once. If already granted, prepare() returns null
        // and we proceed immediately. The user sees a one-time system dialog
        // explaining that the app will manage VPN routing.
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            viewModel.onVpnPermissionGranted()
        }

        // Auto-connect to dashcam WiFi: ensure the necessary "discover WiFi"
        // permission first (Nearby devices on T+, Fine location below).
        ensureWifiPermissionThenConnect()

        setContent {
            RoveDualNetworkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        viewModel,
                        onRetryAutoConnect = ::ensureWifiPermissionThenConnect,
                        onOpenWifiSettings = viewModel::openWifiSettings
                    )
                }
            }
        }
    }

    private fun ensureWifiPermissionThenConnect() {
        if (viewModel.hasWifiPermission()) {
            viewModel.tryAutoConnectWifi()
            return
        }
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        wifiPermissionLauncher.launch(perms.toTypedArray())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRetryAutoConnect: () -> Unit,
    onOpenWifiSettings: () -> Unit
) {
    val net by viewModel.networkState.collectAsStateWithLifecycle()
    val ui  by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Rove Dual Network",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = RoveOrange
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CurrentSsidCard(ui, permissionStatus = viewModel.permissionStatus())
            AutoConnectCard(net, ui, onRetryAutoConnect, onOpenWifiSettings)
            NetworkStatusCard(net, ui)
            DashcamCard(net, ui, viewModel)
            InternetCard(net, ui, viewModel)
            HowItWorksCard()
        }
    }
}

// ── Current SSID Card ─────────────────────────────────────────────────────────

@Composable
fun CurrentSsidCard(ui: UiState, permissionStatus: String) {
    val ssid = ui.currentSsid
    val onRove = ssid?.startsWith("ROVE_R2-4K-DUAL-PRO_") == true
    val color = if (onRove) GreenOnline else Color(0xFFFFA726)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Connected WiFi SSID",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Text(
                text = ssid ?: "—  (waiting for SSID — see permission status below)",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = color
            )
            if (ssid != null && !onRove) {
                Text(
                    text = "Not a dashcam network — expected SSID starting with " +
                           "ROVE_R2-4K-DUAL-PRO_",
                    style = MaterialTheme.typography.bodySmall,
                    color = RedOffline
                )
            }
            Text(
                text = permissionStatus,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color.Gray
            )
        }
    }
}

// ── Auto-connect Card ─────────────────────────────────────────────────────────

@Composable
fun AutoConnectCard(
    net: NetworkState,
    ui: UiState,
    onRetry: () -> Unit,
    onOpenWifiSettings: () -> Unit
) {
    // Hide once dashcam WiFi is connected — the NetworkStatusCard already shows it.
    if (net.wifiReady) return

    val state = ui.autoConnectState
    val (title, color) = when (state) {
        WifiAutoConnector.State.Connecting   -> "Searching for dashcam WiFi…" to RoveOrange
        WifiAutoConnector.State.Connected    -> "Connected" to GreenOnline
        WifiAutoConnector.State.Failed       -> "Auto-connect failed" to RedOffline
        WifiAutoConnector.State.NoPermission -> "Permission needed" to RedOffline
        WifiAutoConnector.State.WifiOff      -> "WiFi is off" to RedOffline
        WifiAutoConnector.State.Unsupported  -> "Manual connect required" to RedOffline
        WifiAutoConnector.State.Idle         -> "Preparing auto-connect…" to RoveOrange
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = color)

            if (ui.autoConnectMessage.isNotEmpty()) {
                Text(
                    text = ui.autoConnectMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            if (state == WifiAutoConnector.State.Connecting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = RoveOrange
                    )
                    Text(
                        "Tap Connect on the system dialog when it appears.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }

            val showRetry = state == WifiAutoConnector.State.Failed ||
                            state == WifiAutoConnector.State.NoPermission ||
                            state == WifiAutoConnector.State.WifiOff ||
                            state == WifiAutoConnector.State.Idle ||
                            state == WifiAutoConnector.State.Unsupported
            if (showRetry) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("Retry auto-connect", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onOpenWifiSettings,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("Open WiFi Settings", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ── Network Status Card ───────────────────────────────────────────────────────

@Composable
fun NetworkStatusCard(net: NetworkState, ui: UiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Network Status",
                style = MaterialTheme.typography.titleMedium,
                color = RoveOrange
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val onRove = ui.currentSsid?.startsWith("ROVE_R2-4K-DUAL-PRO_") == true
                val effectivelyConnected = net.wifiReady || onRove
                val wifiSub = when {
                    !effectivelyConnected      -> "Disconnected"
                    ui.currentSsid != null     -> "${ui.currentSsid}\n192.168.1.253"
                    else                       -> "192.168.1.253"
                }
                NetworkPill(
                    modifier = Modifier.weight(1f),
                    icon = if (effectivelyConnected) Icons.Default.SignalWifi4Bar else Icons.Default.WifiOff,
                    label = "Dashcam WiFi",
                    sublabel = wifiSub,
                    connected = effectivelyConnected
                )
                NetworkPill(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Cloud,
                    label = "Cellular",
                    sublabel = if (net.cellularReady) "Internet active" else "Unavailable",
                    connected = net.cellularReady
                )
            }

            val onRoveForBanner = ui.currentSsid?.startsWith("ROVE_R2-4K-DUAL-PRO_") == true
            if ((net.wifiReady || onRoveForBanner) && net.cellularReady) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(GreenOnline)
                    )
                    Text(
                        text = "Dual-network mode active — dashcam + internet simultaneously",
                        style = MaterialTheme.typography.bodySmall,
                        color = GreenOnline
                    )
                }
            }
        }
    }
}

@Composable
fun NetworkPill(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    sublabel: String,
    connected: Boolean
) {
    val bgColor by animateColorAsState(
        targetValue = if (connected) GreenOnline.copy(alpha = 0.15f)
                      else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(400),
        label = "pill_bg"
    )
    val dotColor by animateColorAsState(
        targetValue = if (connected) GreenOnline else RedOffline,
        animationSpec = tween(400),
        label = "pill_dot"
    )

    Surface(
        modifier = modifier,
        color = bgColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (connected) GreenOnline else Color.Gray,
                    modifier = Modifier.size(18.dp)
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = sublabel,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

// ── Dashcam Card ──────────────────────────────────────────────────────────────

@Composable
fun DashcamCard(net: NetworkState, ui: UiState, vm: MainViewModel) {
    // Treat dashcam as ready whenever EITHER the manager confirmed wifiReady
    // OR the user is on a ROVE_* SSID. The latter covers transient drops of
    // the specifier-granted Network — the user is clearly on the dashcam,
    // and our cached Retrofit is still valid, so buttons must stay tappable.
    val dashcamReady = net.wifiReady ||
        (ui.currentSsid?.startsWith("ROVE_R2-4K-DUAL-PRO_") == true)

    ApiCard(
        title    = "Dashcam API",
        subtitle = "GET 192.168.1.253/?custom=1&cmd=1008&par=2  (via WiFi)",
        icon     = Icons.Default.Videocam,
        enabled  = dashcamReady,
        loading  = ui.dashcamLoading,
        result   = ui.dashcamResult,
        buttons  = {
            ActionButton(
                label   = "Live Video",
                enabled = dashcamReady && !ui.dashcamLoading
            ) { vm.showLiveVideo() }
            ActionButton(
                label   = "Dashcam Video",
                enabled = dashcamReady && !ui.dashcamLoading
            ) { vm.showDashcamVideo() }
            ActionButton(
                label   = "Settings",
                enabled = dashcamReady && !ui.dashcamLoading
            ) { vm.showSettings() }
            ActionButton(
                label   = "Cmd 3022",
                enabled = dashcamReady && !ui.dashcamLoading
            ) { vm.sendCmd3022() }
        }
    )
}

// ── Internet Card ─────────────────────────────────────────────────────────────

@Composable
fun InternetCard(net: NetworkState, ui: UiState, vm: MainViewModel) {
    ApiCard(
        title    = "Internet (Cellular)",
        subtitle = "GET https://www.google.com  (via Cellular)",
        icon     = Icons.Default.Cloud,
        enabled  = net.cellularReady,
        loading  = ui.internetLoading,
        result   = ui.internetResult,
        buttons  = {
            ActionButton(
                label   = "Fetch Google",
                enabled = net.cellularReady && !ui.internetLoading
            ) { vm.fetchGoogle() }
        }
    )
}

// ── Shared ApiCard ────────────────────────────────────────────────────────────

@Composable
fun ApiCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean,
    loading: Boolean,
    result: String,
    buttons: @Composable RowScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = RoveOrange,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = RoveOrange
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }

            if (!enabled) {
                Text(
                    text = if (title.contains("Dashcam"))
                        "Connect to dashcam WiFi to enable"
                    else
                        "Enable mobile data to use internet API",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFA726)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                buttons()
            }

            if (loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = RoveOrange
                    )
                    Text("Loading…", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }

            if (result.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = result,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
fun RowScope.ActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        modifier = Modifier.height(36.dp)
    ) {
        Text(label, fontSize = 12.sp)
    }
}

// ── How It Works Card ─────────────────────────────────────────────────────────

@Composable
fun HowItWorksCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "How it works",
                style = MaterialTheme.typography.titleMedium,
                color = RoveOrange
            )
            val lines = listOf(
                "1. registerNetworkCallback(WiFi) → watches dashcam connection",
                "2. requestNetwork(CELLULAR) → forces Android to keep cellular alive",
                "3. Each OkHttpClient binds to network.socketFactory",
                "4. Dashcam Retrofit → WiFi socket → 192.168.1.253",
                "5. Internet Retrofit → Cellular socket → any internet host",
                "",
                "Min SDK 21 (Android 5.0+) required."
            )
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (line.startsWith(" ") || line.isEmpty()) FontFamily.Default
                                 else FontFamily.Default,
                    color = if (line.isEmpty()) Color.Transparent
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}
