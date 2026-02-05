package com.ropex.pptapp.ui.theme

import android.app.Activity
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

// Modern Color Palette - Light Theme
val ModernLightPrimary = Color(0xFF6750A4)    // Rich purple
val ModernLightSecondary = Color(0xFF006A6A)   // Teal
val ModernLightTertiary = Color(0xFF7D5260)    // Mauve
val ModernLightBackground = Color(0xFFFEF7FF)  // Very light purple tint
val ModernLightSurface = Color(0xFFFEF7FF)
val ModernLightError = Color(0xFFBA1A1A)       // Coral red

// Modern Color Palette - Dark Theme
val ModernDarkPrimary = Color(0xFFD0BCFF)      // Light purple
val ModernDarkSecondary = Color(0xFF4CD8D8)    // Bright teal
val ModernDarkTertiary = Color(0xFFEFB8C8)     // Light pink
val ModernDarkBackground = Color(0xFF141218)   // Deep gray with purple tint
val ModernDarkSurface = Color(0xFF211F26)
val ModernDarkError = Color(0xFFFFB4AB)        // Soft red

private val DarkColorScheme = darkColorScheme(
    primary = ModernDarkPrimary,
    secondary = ModernDarkSecondary,
    tertiary = ModernDarkTertiary,
    background = ModernDarkBackground,
    surface = ModernDarkSurface,
    error = ModernDarkError,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = Color(0xFFE6E1E5),
    onSurface = Color(0xFFE6E1E5),
    onError = Color.Black
)

private val LightColorScheme = lightColorScheme(
    primary = ModernLightPrimary,
    secondary = ModernLightSecondary,
    tertiary = ModernLightTertiary,
    background = ModernLightBackground,
    surface = ModernLightSurface,
    error = ModernLightError,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    onError = Color.White
)

@Composable
fun PPTAPPTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}