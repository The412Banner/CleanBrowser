package com.banner.cleanbrowser.ui

import android.os.Build
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.banner.cleanbrowser.core.Settings
import com.banner.cleanbrowser.core.ThemeMode

object Accents {
    val list = listOf(
        "Blue" to Color(0xFF2F6FED),
        "Teal" to Color(0xFF0E9384),
        "Green" to Color(0xFF2E9E4F),
        "Indigo" to Color(0xFF4F5BD5),
        "Purple" to Color(0xFF7B5CD6),
        "Pink" to Color(0xFFD6457E),
        "Red" to Color(0xFFD64545),
        "Orange" to Color(0xFFE0782F),
        "Amber" to Color(0xFFC99A1E),
        "Graphite" to Color(0xFF5F6B7A),
    )

    fun color(i: Int) = list.getOrElse(i) { list[0] }.second
}

val LocalMotion = staticCompositionLocalOf { true }
val LocalDark = staticCompositionLocalOf { false }

object Motion {
    @Composable
    @ReadOnlyComposable
    fun <T> fast(): FiniteAnimationSpec<T> = if (LocalMotion.current) tween(160) else snap()

    @Composable
    @ReadOnlyComposable
    fun <T> medium(): FiniteAnimationSpec<T> = if (LocalMotion.current) tween(240) else snap()

    @Composable
    @ReadOnlyComposable
    fun <T> bouncy(): FiniteAnimationSpec<T> = if (LocalMotion.current) spring(dampingRatio = 0.82f, stiffness = 520f) else snap()
}

@Composable
fun isDark(s: Settings): Boolean = when (s.themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

private fun lightFrom(a: Color): ColorScheme {
    val n = Color(0xFFF7F8FA)
    return lightColorScheme(
        primary = a,
        onPrimary = Color.White,
        primaryContainer = lerp(a, Color.White, 0.84f),
        onPrimaryContainer = lerp(a, Color.Black, 0.62f),
        inversePrimary = lerp(a, Color.White, 0.45f),
        secondary = lerp(a, Color(0xFF5F6670), 0.55f),
        onSecondary = Color.White,
        secondaryContainer = lerp(a, Color.White, 0.9f),
        onSecondaryContainer = lerp(a, Color.Black, 0.65f),
        tertiary = lerp(a, Color(0xFF7A5C9E), 0.5f),
        background = Color(0xFFFDFDFE),
        onBackground = Color(0xFF1A1C20),
        surface = Color(0xFFFDFDFE),
        onSurface = Color(0xFF1A1C20),
        surfaceVariant = lerp(n, a, 0.04f),
        onSurfaceVariant = Color(0xFF5B606A),
        surfaceTint = a,
        outline = Color(0xFFC3C7CF),
        outlineVariant = Color(0xFFE2E4E9),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = lerp(Color(0xFFF8F9FB), a, 0.015f),
        surfaceContainer = lerp(Color(0xFFF2F3F6), a, 0.025f),
        surfaceContainerHigh = lerp(Color(0xFFECEEF2), a, 0.03f),
        surfaceContainerHighest = lerp(Color(0xFFE6E8ED), a, 0.035f),
        surfaceBright = Color(0xFFFDFDFE),
        surfaceDim = Color(0xFFDCDEE3),
    )
}

private fun darkFrom(a: Color, black: Boolean): ColorScheme {
    val p = lerp(a, Color.White, 0.3f)
    val base = if (black) Color.Black else Color(0xFF121316)
    fun c(light: Long, dark: Long) = if (black) Color(dark) else Color(light)
    return darkColorScheme(
        primary = p,
        onPrimary = lerp(a, Color.Black, 0.72f),
        primaryContainer = lerp(a, Color.Black, 0.52f),
        onPrimaryContainer = lerp(a, Color.White, 0.82f),
        inversePrimary = a,
        secondary = lerp(p, Color(0xFFB8BEC8), 0.5f),
        onSecondary = Color(0xFF1E2228),
        secondaryContainer = lerp(a, Color(0xFF2A2D33), 0.78f),
        onSecondaryContainer = Color(0xFFE2E5EA),
        tertiary = lerp(p, Color(0xFFCDB8EA), 0.5f),
        background = base,
        onBackground = Color(0xFFE4E5E9),
        surface = base,
        onSurface = Color(0xFFE4E5E9),
        surfaceVariant = lerp(c(0xFF24262B, 0xFF16171A), a, 0.05f),
        onSurfaceVariant = Color(0xFFA7ACB5),
        surfaceTint = p,
        outline = Color(0xFF5C616B),
        outlineVariant = c(0xFF2E3137, 0xFF222428),
        surfaceContainerLowest = c(0xFF0D0E10, 0xFF000000),
        surfaceContainerLow = lerp(c(0xFF17181C, 0xFF08090A), a, 0.02f),
        surfaceContainer = lerp(c(0xFF1B1C20, 0xFF0E0F11), a, 0.025f),
        surfaceContainerHigh = lerp(c(0xFF222328, 0xFF16171A), a, 0.03f),
        surfaceContainerHighest = lerp(c(0xFF2A2C31, 0xFF1D1E22), a, 0.035f),
        surfaceBright = c(0xFF35373D, 0xFF222428),
        surfaceDim = base,
    )
}

private fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF08090A),
    surfaceContainer = Color(0xFF0E0F11),
    surfaceContainerHigh = Color(0xFF16171A),
    surfaceContainerHighest = Color(0xFF1D1E22),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val AppType = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

@Composable
fun CleanTheme(s: Settings, privateMode: Boolean = false, content: @Composable () -> Unit) {
    val dark = isDark(s) || privateMode
    val ctx = LocalContext.current
    val scheme = when {
        s.wallpaperColors && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx).let { if (s.pureBlack) it.pureBlack() else it } else dynamicLightColorScheme(ctx)
        privateMode -> darkFrom(Color(0xFF8A63D2), s.pureBlack)
        dark -> darkFrom(Accents.color(s.accent), s.pureBlack)
        else -> lightFrom(Accents.color(s.accent))
    }
    CompositionLocalProvider(LocalMotion provides s.animations, LocalDark provides dark) {
        MaterialTheme(colorScheme = scheme, shapes = AppShapes, typography = AppType, content = content)
    }
}
