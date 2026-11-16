package nl.kleuren.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import nl.kleuren.app.data.ThemeMode

// Eén losse kleur voor foutmeldingen.
private val ColorError = Color(0xFF8C3B32)

private val LightColors = lightColorScheme(
    primary = Sage,
    onPrimary = Paper,
    primaryContainer = SageLight,
    onPrimaryContainer = Ink,
    secondary = Terracotta,
    onSecondary = Paper,
    secondaryContainer = TerracottaLight,
    onSecondaryContainer = Ink,
    tertiary = SeaBlue,
    onTertiary = Paper,
    background = Cream,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Sand,
    onSurfaceVariant = InkSoft,
    outline = OutlineSoft,
    outlineVariant = Sand,
    error = ColorError,
)

private val DarkColors = darkColorScheme(
    primary = SageDark,
    onPrimary = NightBackground,
    primaryContainer = SageContainerDark,
    onPrimaryContainer = SageLight,
    secondary = TerracottaDark,
    onSecondary = NightBackground,
    secondaryContainer = TerracottaContainerDark,
    onSecondaryContainer = TerracottaLight,
    tertiary = SeaBlueDark,
    onTertiary = NightBackground,
    background = NightBackground,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightInkSoft,
    outline = OutlineDark,
    outlineVariant = NightSurfaceVariant,
    error = Color(0xFFF2B8B5),
)

private val KleurenShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Past het Material 3-thema toe op alles hieronder.
 *
 * [themeMode] komt uit de instellingen. Bij SYSTEM volgen we het toestel;
 * anders forceren we licht of donker. De systeembalk-icoontjes wisselen mee.
 */
@Composable
fun KleurenTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = KleurenTypography,
        shapes = KleurenShapes,
        content = content,
    )
}

