package io.github.akudamatata.solara

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.akudamatata.solara.ui.solaraColors
import org.junit.Assert.*
import org.junit.Test

class ThemeTest {
    @Test fun endfieldTextRemainsReadableInLightAndDarkModes() {
        for (dark in listOf(false, true)) {
            val colors = solaraColors(dark, "endfield")
            val textPairs = listOf(
                colors.onSurface to colors.surface,
                colors.onSurfaceVariant to colors.surfaceContainer,
                colors.onBackground to colors.background,
                colors.primary to colors.surface,
                colors.onPrimary to colors.primary,
                colors.onPrimaryContainer to colors.primaryContainer,
                colors.onSecondaryContainer to colors.secondaryContainer,
                colors.inverseOnSurface to colors.inverseSurface,
            )
            for ((text, background) in textPairs) {
                val light = maxOf(text.luminance(), background.luminance())
                val darkValue = minOf(text.luminance(), background.luminance())
                assertTrue("主题 dark=$dark 的文字对比度不足", (light + 0.05f) / (darkValue + 0.05f) >= 4.5f)
            }
        }
    }

    @Test fun defaultAndUnknownStylesRetainOriginalSolaraColors() {
        for (style in listOf("default", "unknown")) {
            assertEquals(Color(0xFF12836D), solaraColors(false, style).primary)
            assertEquals(Color(0xFFF0F8F3), solaraColors(false, style).background)
            assertEquals(Color(0xFF62DCC1), solaraColors(true, style).primary)
            assertEquals(Color(0xFF0B1D1B), solaraColors(true, style).background)
        }
    }
}
