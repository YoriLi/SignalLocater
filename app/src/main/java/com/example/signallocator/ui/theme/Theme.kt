package com.example.signallocator.ui.theme
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
private val DarkScheme = darkColorScheme(primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80)
private val LightScheme = lightColorScheme(primary = Purple40, secondary = PurpleGrey40, tertiary = Pink40)

@Composable
fun SignalLocatorTheme(darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> { val ctx = LocalContext.current; if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx) }
        darkTheme -> DarkScheme; else -> LightScheme
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
