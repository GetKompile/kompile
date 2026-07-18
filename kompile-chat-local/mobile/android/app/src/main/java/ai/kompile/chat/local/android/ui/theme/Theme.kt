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

private val DarkColors = darkColorScheme(
    primary        = Color(0xFF90CAF9),
    onPrimary      = Color(0xFF003258),
    primaryContainer  = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary      = Color(0xFFBBC8DA),
    onSecondary    = Color(0xFF253240),
    background     = Color(0xFF1A1C1E),
    onBackground   = Color(0xFFE2E2E5),
    surface        = Color(0xFF1A1C1E),
    onSurface      = Color(0xFFE2E2E5),
    surfaceVariant = Color(0xFF41484D),
    onSurfaceVariant = Color(0xFFC1C7CE)
)

private val LightColors = lightColorScheme(
    primary        = Color(0xFF005FAF),
    onPrimary      = Color(0xFFFFFFFF),
    primaryContainer  = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary      = Color(0xFF536378),
    onSecondary    = Color(0xFFFFFFFF),
    background     = Color(0xFFFCFCFF),
    onBackground   = Color(0xFF1A1C1E),
    surface        = Color(0xFFFCFCFF),
    onSurface      = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDEE3EB),
    onSurfaceVariant = Color(0xFF41484D)
)

@Composable
fun KompileChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Material You dynamic colors on Android 12+.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
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
