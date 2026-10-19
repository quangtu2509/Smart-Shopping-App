package com.toked.smartshopping.ui.theme

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

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF78D8B5),
    onPrimary = Color(0xFF003829),
    primaryContainer = Color(0xFF00513C),
    onPrimaryContainer = Color(0xFF94F5D0),
    secondary = Color(0xFFBCC9C5),
    secondaryContainer = Color(0xFF283C35),
    onSecondaryContainer = Color(0xFF94F5D0),
    background = Color(0xFF101820),
    onBackground = Color(0xFFE3EAF3),
    surface = Color(0xFF101820),
    onSurface = Color(0xFFE3EAF3),
    surfaceContainerLowest = Color(0xFF18232D),
    surfaceContainerLow = Color(0xFF202F3A),
    surfaceContainer = Color(0xFF293945),
    surfaceContainerHigh = Color(0xFF32434F),
    surfaceContainerHighest = Color(0xFF3A4B57),
    onSurfaceVariant = Color(0xFFBCC9C5),
    outlineVariant = Color(0xFF43544C),
)

private val LightColorScheme = lightColorScheme(
    primary = ShoppingGreen,
    onPrimary = Color.White,
    primaryContainer = ShoppingGreenContainer,
    onPrimaryContainer = Color(0xFFF5FFF8),
    secondary = ShoppingMuted,
    secondaryContainer = ShoppingBadge,
    onSecondaryContainer = ShoppingGreen,
    background = ShoppingBackground,
    onBackground = ShoppingInk,
    surface = ShoppingBackground,
    onSurface = ShoppingInk,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = ShoppingField,
    surfaceContainer = Color(0xFFE6EEFF),
    surfaceContainerHigh = Color(0xFFDEE9FC),
    surfaceContainerHighest = Color(0xFFD9E3F6),
    onSurfaceVariant = ShoppingMuted,
    outline = Color(0xFF6E7A73),
    outlineVariant = ShoppingBorder,
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
)

@Composable
fun SmartShoppingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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
