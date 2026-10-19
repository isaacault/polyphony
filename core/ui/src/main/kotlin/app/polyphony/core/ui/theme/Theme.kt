package app.polyphony.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.polyphony.core.domain.repository.ThemeMode
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

/** Default seed when neither dynamic colour nor a user accent is available. */
val DefaultSeedColor = Color(0xFF6750A4)

/** Exposes whether the current theme is dark, so components can pick contrast-safe overlays. */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/** True when pure black backgrounds are active (OLED mode). */
val LocalPureBlack = staticCompositionLocalOf { false }

@Composable
fun PolyphonyTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    seedColor: Color = DefaultSeedColor,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val canUseDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val base: ColorScheme = if (canUseDynamic) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        rememberDynamicColorScheme(
            seedColor = seedColor,
            isDark = dark,
            style = PaletteStyle.TonalSpot,
        )
    }
    val scheme = if (dark && pureBlack) base.toPureBlack() else base

    CompositionLocalProvider(
        LocalIsDarkTheme provides dark,
        LocalPureBlack provides (dark && pureBlack),
    ) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            typography = PolyphonyTypography,
            shapes = PolyphonyShapes,
            content = content,
        )
    }
}

/**
 * Re-themes a subtree from a colour extracted from artwork (used by the Now Playing screen).
 * Falls back to the ambient theme when [seed] is null.
 */
@Composable
fun ArtworkTheme(
    seed: Color?,
    style: PaletteStyle = PaletteStyle.Vibrant,
    content: @Composable () -> Unit,
) {
    if (seed == null) {
        content()
        return
    }
    val dark = LocalIsDarkTheme.current
    val pureBlack = LocalPureBlack.current
    val scheme = rememberDynamicColorScheme(seedColor = seed, isDark = dark, style = style)
    MaterialExpressiveTheme(
        colorScheme = if (pureBlack) scheme.toPureBlack() else scheme,
        motionScheme = MotionScheme.expressive(),
        typography = PolyphonyTypography,
        shapes = PolyphonyShapes,
        content = content,
    )
}

private fun ColorScheme.toPureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF222222),
    surfaceDim = Color.Black,
)

/** Lightweight schemes for previews. */
internal val PreviewLightScheme = lightColorScheme()
internal val PreviewDarkScheme = darkColorScheme()
