package app.polyphony.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Default = Typography()

/**
 * Material 3 type scale with slightly tighter, heavier titles — close to Symfonium's dense but
 * legible lists. Uses the system font so it follows the user's font preference.
 */
val PolyphonyTypography = Typography(
    displayLarge = Default.displayLarge.copy(fontFamily = FontFamily.Default),
    displayMedium = Default.displayMedium,
    displaySmall = Default.displaySmall,
    headlineLarge = Default.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = Default.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineSmall = Default.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Default.titleSmall.copy(fontWeight = FontWeight.Medium),
    bodyLarge = Default.bodyLarge,
    bodyMedium = Default.bodyMedium,
    bodySmall = Default.bodySmall,
    labelLarge = Default.labelLarge.copy(fontWeight = FontWeight.Medium),
    labelMedium = Default.labelMedium,
    labelSmall = Default.labelSmall,
)

/** Large, bold track title for the Now Playing screen. */
val NowPlayingTitleStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 24.sp,
    lineHeight = 30.sp,
    letterSpacing = (-0.25).sp,
)
