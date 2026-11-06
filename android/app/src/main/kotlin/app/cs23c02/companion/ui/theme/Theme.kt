package app.cs23c02.companion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * docs/ux/design-system.md: calm, readable, one theme module, light and dark from day one.
 * Every text/background pair below is ≥ 4.5:1 (WCAG AA); correct/incorrect never rely on colour alone.
 */
private val Light = lightColorScheme(
    primary = Color(0xFF1B5E9E), onPrimary = Color.White,          // 6.4:1
    primaryContainer = Color(0xFFD6E6F7), onPrimaryContainer = Color(0xFF0B2A47),
    secondary = Color(0xFF4A5A6A), onSecondary = Color.White,
    background = Color(0xFFFBFCFE), onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFBFCFE), onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFEEF1F5), onSurfaceVariant = Color(0xFF41474D), // 8.3:1
    error = Color(0xFFB3261E), onError = Color.White,               // 6.0:1
    errorContainer = Color(0xFFFCE8E6), onErrorContainer = Color(0xFF5C0F0A),
    outline = Color(0xFF6F7780),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA6CBF2), onPrimary = Color(0xFF07304F),
    primaryContainer = Color(0xFF1E4468), onPrimaryContainer = Color(0xFFD6E6F7),
    secondary = Color(0xFFB9C7D6), onSecondary = Color(0xFF233240),
    background = Color(0xFF121416), onBackground = Color(0xFFE2E4E7),
    surface = Color(0xFF121416), onSurface = Color(0xFFE2E4E7),
    surfaceVariant = Color(0xFF2A2F35), onSurfaceVariant = Color(0xFFC3C9D0),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF5C1A15), onErrorContainer = Color(0xFFFCDAD7),
    outline = Color(0xFF8D949B),
)

/** Scale 28 / 22 / 18 / 16 body / 14 caption, body line height 1.5. Sizes are in sp, so the system font scale applies. */
private val Type = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
)

val CodeFont = FontFamily.Monospace

@Composable
fun CompanionTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
}
