package com.rove.dualnetwork.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary        = RoveOrange,
    onPrimary      = RoveDark,
    primaryContainer   = RoveOrangeDark,
    background     = RoveDark,
    surface        = RoveSurface,
    surfaceVariant = RoveGray,
    onBackground   = androidx.compose.ui.graphics.Color.White,
    onSurface      = androidx.compose.ui.graphics.Color.White
)

@Composable
fun RoveDualNetworkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography  = Typography,
        content     = content
    )
}
