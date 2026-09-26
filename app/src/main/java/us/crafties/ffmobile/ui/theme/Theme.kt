package us.crafties.ffmobile.ui.theme

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

private val Seed = Color(0xFF6750A4)
private val Accent = Color(0xFF03DAC6)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCFBCFF),
    onPrimary = Color(0xFF381E72),
    secondary = Accent,
    tertiary = Color(0xFFEFB8C8),
    background = Color(0xFF101014),
    surface = Color(0xFF17171C),
    surfaceVariant = Color(0xFF232329),
)

private val LightColors = lightColorScheme(
    primary = Seed,
    secondary = Color(0xFF00695C),
    tertiary = Color(0xFF7D5260),
    background = Color(0xFFFCF8FD),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun FFMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FFMobileTypography,
        content = content
    )
}
