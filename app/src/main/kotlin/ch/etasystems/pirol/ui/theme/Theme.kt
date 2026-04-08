package ch.etasystems.pirol.ui.theme

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

// Pirol-Gelb als Primaerfarbe (Oriolus oriolus Gefieder)
private val PirolYellow = Color(0xFFE6A817)
private val PirolDark = Color(0xFF1C1B1F)
private val PirolSurface = Color(0xFFFFFBFE)

private val LightColorScheme = lightColorScheme(
    primary = PirolYellow,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFFFFF0C7),
    onPrimaryContainer = Color(0xFF261A00),
    secondary = Color(0xFF6D5E0F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF8E287),
    onSecondaryContainer = Color(0xFF221B00),
    tertiary = Color(0xFF4A6546),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCCEBC4),
    onTertiaryContainer = Color(0xFF072108),
    surface = PirolSurface,
    onSurface = PirolDark,
    surfaceVariant = Color(0xFFEDE1CF),
    onSurfaceVariant = Color(0xFF4D4639)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFD54F),
    onPrimary = Color(0xFF3F2E00),
    primaryContainer = Color(0xFF5C4300),
    onPrimaryContainer = Color(0xFFFFF0C7),
    secondary = Color(0xFFDBC66E),
    onSecondary = Color(0xFF3A3000),
    secondaryContainer = Color(0xFF534600),
    onSecondaryContainer = Color(0xFFF8E287),
    tertiary = Color(0xFFB0CFAA),
    onTertiary = Color(0xFF1D361B),
    tertiaryContainer = Color(0xFF334D30),
    onTertiaryContainer = Color(0xFFCCEBC4),
    surface = PirolDark,
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF4D4639),
    onSurfaceVariant = Color(0xFFD0C5B4)
)

@Composable
fun PirolTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Dynamic Color ab Android 12 (API 31)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PirolTypography,
        content = content
    )
}
