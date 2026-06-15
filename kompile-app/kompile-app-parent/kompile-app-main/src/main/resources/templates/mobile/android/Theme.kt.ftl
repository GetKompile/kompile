package {{packageName}}.ui.theme

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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = KompilePrimaryLight,
    onPrimary = KompilePrimaryDark,
    primaryContainer = KompilePrimaryDark,
    onPrimaryContainer = KompilePrimaryLight,
    secondary = KompileSecondaryLight,
    onSecondary = KompileSecondaryDark,
    secondaryContainer = KompileSecondaryDark,
    onSecondaryContainer = KompileSecondaryLight,
    tertiary = KompileTertiaryLight,
    onTertiary = KompileTertiaryDark,
    tertiaryContainer = KompileTertiaryDark,
    onTertiaryContainer = KompileTertiaryLight,
    background = SurfaceDark,
    onBackground = SurfaceLight,
    surface = SurfaceDark,
    onSurface = SurfaceLight,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = SurfaceVariantLight,
    error = ErrorColor
)

private val LightColorScheme = lightColorScheme(
    primary = KompilePrimary,
    onPrimary = SurfaceLight,
    primaryContainer = KompilePrimaryLight,
    onPrimaryContainer = KompilePrimaryDark,
    secondary = KompileSecondary,
    onSecondary = SurfaceLight,
    secondaryContainer = KompileSecondaryLight,
    onSecondaryContainer = KompileSecondaryDark,
    tertiary = KompileTertiary,
    onTertiary = SurfaceLight,
    tertiaryContainer = KompileTertiaryLight,
    onTertiaryContainer = KompileTertiaryDark,
    background = SurfaceLight,
    onBackground = SurfaceDark,
    surface = SurfaceLight,
    onSurface = SurfaceDark,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = SurfaceVariantDark,
    error = ErrorColor
)

@Composable
fun {{projectName}}Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
