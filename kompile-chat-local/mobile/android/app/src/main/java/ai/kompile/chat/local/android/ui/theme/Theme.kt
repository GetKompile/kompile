package ai.kompile.chat.local.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Kompile brand blue — the fill color of the brand mark bundled with
 * kompile-app-main (src/main/frontend/src/assets/branding/kompile-logo.svg)
 * and mirrored here as drawable/kompile_logo.xml.
 */
val KompileBlue = Color(0xFF2563EB)

private val DarkColors = darkColorScheme(
    primary        = Color(0xFFB4C5FF),
    onPrimary      = Color(0xFF002B75),
    primaryContainer  = Color(0xFF1D4ED8),
    onPrimaryContainer = Color(0xFFDCE1FF),
    secondary      = Color(0xFFBFC6DC),
    onSecondary    = Color(0xFF293041),
    secondaryContainer = Color(0xFF3F4759),
    onSecondaryContainer = Color(0xFFDBE2F9),
    tertiary       = Color(0xFF4FD8EB),
    onTertiary     = Color(0xFF00363D),
    tertiaryContainer = Color(0xFF004F58),
    onTertiaryContainer = Color(0xFF97F0FF),
    background     = Color(0xFF121318),
    onBackground   = Color(0xFFE3E2E9),
    surface        = Color(0xFF121318),
    onSurface      = Color(0xFFE3E2E9),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline        = Color(0xFF8F9099)
)

private val LightColors = lightColorScheme(
    primary        = KompileBlue,
    onPrimary      = Color(0xFFFFFFFF),
    primaryContainer  = Color(0xFFDCE1FF),
    onPrimaryContainer = Color(0xFF001550),
    secondary      = Color(0xFF575E71),
    onSecondary    = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDBE2F9),
    onSecondaryContainer = Color(0xFF141B2C),
    tertiary       = Color(0xFF006874),
    onTertiary     = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF97F0FF),
    onTertiaryContainer = Color(0xFF001F24),
    background     = Color(0xFFFDFBFF),
    onBackground   = Color(0xFF1A1B20),
    surface        = Color(0xFFFDFBFF),
    onSurface      = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44464F),
    outline        = Color(0xFF757780)
)

@Composable
fun KompileChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Kompile branding is the default. Material You wallpaper colors replace the
    // entire brand palette, so they are opt-in rather than automatic on Android 12+.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme  -> DarkColors
        else       -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
