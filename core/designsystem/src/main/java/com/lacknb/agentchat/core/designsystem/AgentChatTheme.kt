package com.lacknb.agentchat.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF296A63),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB5D5CE),
    onPrimaryContainer = Color(0xFF06201C),
    secondary = Color(0xFF6C5D00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5E287),
    onSecondaryContainer = Color(0xFF211B00),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color.White,
    surface = Color(0xFFFCFCF8),
    onSurface = Color(0xFF1A1C1A),
    surfaceVariant = Color(0xFFE0E4DF),
    onSurfaceVariant = Color(0xFF434843),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9AC9C0),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF0D4F47),
    onPrimaryContainer = Color(0xFFB5D5CE),
    secondary = Color(0xFFD8C66E),
    onSecondary = Color(0xFF383000),
    secondaryContainer = Color(0xFF514700),
    onSecondaryContainer = Color(0xFFF5E287),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    surface = Color(0xFF111411),
    onSurface = Color(0xFFE2E3DE),
    surfaceVariant = Color(0xFF434843),
    onSurfaceVariant = Color(0xFFC3C8C2),
)

@Composable
fun AgentChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
