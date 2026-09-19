package io.github.akudamatata.solara.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akudamatata.solara.data.Settings

val LocalEndfieldTheme = staticCompositionLocalOf { false }

// 配色、切角与标签字体统一在这里定义，页面继续使用 MaterialTheme。
@Composable
fun SolaraTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = settings.theme == "dark" || (settings.theme == "system" && isSystemInDarkTheme())
    val endfield = settings.themeStyle == "endfield"
    val typography = Typography()
    CompositionLocalProvider(LocalEndfieldTheme provides endfield) {
        MaterialTheme(
            colorScheme = solaraColors(dark, settings.themeStyle),
            shapes = if (endfield) Shapes(CutCornerShape(2.dp), CutCornerShape(4.dp), CutCornerShape(8.dp),
                CutCornerShape(12.dp), CutCornerShape(16.dp)) else Shapes(),
            typography = if (endfield) typography.copy(
                titleLarge = typography.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                labelSmall = typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                labelMedium = typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            ) else typography,
            content = content,
        )
    }
}

@Composable
fun solaraShape(defaultRadius: Dp): Shape =
    if (LocalEndfieldTheme.current) MaterialTheme.shapes.medium else RoundedCornerShape(defaultRadius)

fun solaraColors(dark: Boolean, style: String): ColorScheme {
    if (style == "endfield") return if (dark) darkColorScheme(
        primary = Color(0xFFF4E750), onPrimary = Color(0xFF24230E),
        primaryContainer = Color(0xFF454116), onPrimaryContainer = Color(0xFFFFF278),
        secondary = Color(0xFFC6C8B8), onSecondary = Color(0xFF2D3027),
        secondaryContainer = Color(0xFF3D4036), onSecondaryContainer = Color(0xFFE3E6D6),
        tertiary = Color(0xFFA5CCD8), onTertiary = Color(0xFF12343F),
        tertiaryContainer = Color(0xFF2B4B56), onTertiaryContainer = Color(0xFFC1E8F5),
        background = Color(0xFF151714), onBackground = Color(0xFFE7E8DF),
        surface = Color(0xFF1B1E19), onSurface = Color(0xFFE7E8DF),
        surfaceVariant = Color(0xFF404339), onSurfaceVariant = Color(0xFFC3C7B9),
        surfaceContainerLowest = Color(0xFF10120F), surfaceContainerLow = Color(0xFF191C17),
        surfaceContainer = Color(0xFF22251F), surfaceContainerHigh = Color(0xFF2C2F28),
        surfaceContainerHighest = Color(0xFF363931), surfaceDim = Color(0xFF151714), surfaceBright = Color(0xFF3B3E36),
        outline = Color(0xFF8E9285), outlineVariant = Color(0xFF44483D),
        inverseSurface = Color(0xFFE7E8DF), inverseOnSurface = Color(0xFF2D3027), inversePrimary = Color(0xFF655C00),
        surfaceTint = Color(0xFFF4E750),
    ) else lightColorScheme(
        // 浅色下文字使用深黄色，亮黄用于容器，避免黄字在浅色背景上看不清。
        primary = Color(0xFF655C00), onPrimary = Color.White,
        primaryContainer = Color(0xFFF4E750), onPrimaryContainer = Color(0xFF24230E),
        secondary = Color(0xFF595D50), onSecondary = Color.White,
        secondaryContainer = Color(0xFFE1E5D5), onSecondaryContainer = Color(0xFF1C2116),
        tertiary = Color(0xFF42616C), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFC1E8F5), onTertiaryContainer = Color(0xFF00202A),
        background = Color(0xFFF1F1E8), onBackground = Color(0xFF1C1F18),
        surface = Color(0xFFFAFAF2), onSurface = Color(0xFF1C1F18),
        surfaceVariant = Color(0xFFE2E5D8), onSurfaceVariant = Color(0xFF45483D),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F5EB),
        surfaceContainer = Color(0xFFEBECE2), surfaceContainerHigh = Color(0xFFE4E6DB),
        surfaceContainerHighest = Color(0xFFDEE0D5), surfaceDim = Color(0xFFDADCD1), surfaceBright = Color(0xFFFAFAF2),
        outline = Color(0xFF74796A), outlineVariant = Color(0xFFC5C9BA),
        inverseSurface = Color(0xFF2D3027), inverseOnSurface = Color(0xFFF0F2E7), inversePrimary = Color(0xFFD9CC36),
        surfaceTint = Color(0xFF655C00),
    )
    // 保留原 Solara 配色，未选择新风格时外观不变。
    return if (dark) darkColorScheme(
        primary = Color(0xFF62DCC1), onPrimary = Color(0xFF053D33),
        primaryContainer = Color(0xFF164C42), onPrimaryContainer = Color(0xFFB4F4DF),
        background = Color(0xFF0B1D1B), surface = Color(0xFF122925),
        surfaceContainer = Color(0xFF19342E), surfaceContainerHigh = Color(0xFF203B35),
        onSurface = Color(0xFFE5F4EE), onSurfaceVariant = Color(0xFFA8C0B7),
    ) else lightColorScheme(
        primary = Color(0xFF12836D), onPrimary = Color.White,
        primaryContainer = Color(0xFFC4EDDF), onPrimaryContainer = Color(0xFF174E42),
        background = Color(0xFFF0F8F3), surface = Color(0xFFF8FCF9),
        surfaceContainer = Color(0xFFE5F2EA), surfaceContainerHigh = Color(0xFFDDEDE3),
        onSurface = Color(0xFF243F36), onSurfaceVariant = Color(0xFF617B70),
    )
}
