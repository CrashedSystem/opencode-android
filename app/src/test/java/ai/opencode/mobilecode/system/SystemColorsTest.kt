package ai.opencode.mobilecode.system

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemColorsTest {
    @Test
    fun lightMatchesApple() {
        LightPalette.label.assertRgb(0, 0, 0)
        LightPalette.secondaryLabel.assertRgb(60, 60, 67, 0.60f)
        LightPalette.tertiaryLabel.assertRgb(60, 60, 67, 0.30f)
        LightPalette.systemBackground.assertRgb(255, 255, 255)
        LightPalette.secondarySystemBackground.assertRgb(242, 242, 247)
        LightPalette.tertiarySystemFill.assertRgb(118, 118, 128, 0.12f)
        LightPalette.separator.assertRgb(60, 60, 67, 0.29f)
        LightPalette.systemBlue.assertRgb(0, 136, 255)
        LightPalette.link.assertRgb(0, 136, 255)
        LightPalette.tint.assertRgb(0, 136, 255)
        LightPalette.systemOrange.assertRgb(255, 141, 40)
        LightPalette.systemRed.assertRgb(255, 59, 48)
        LightPalette.systemGreen.assertRgb(52, 199, 89)
    }

    @Test
    fun darkMatchesApple() {
        DarkPalette.label.assertRgb(255, 255, 255)
        DarkPalette.secondaryLabel.assertRgb(235, 235, 245, 0.60f)
        DarkPalette.tertiaryLabel.assertRgb(235, 235, 245, 0.30f)
        DarkPalette.systemBackground.assertRgb(0, 0, 0)
        DarkPalette.secondarySystemBackground.assertRgb(28, 28, 30)
        DarkPalette.tertiarySystemFill.assertRgb(118, 118, 128, 0.24f)
        DarkPalette.separator.assertRgb(84, 84, 88, 0.60f)
        DarkPalette.systemBlue.assertRgb(0, 145, 255)
        DarkPalette.link.assertRgb(0, 145, 255)
        DarkPalette.tint.assertRgb(0, 145, 255)
        DarkPalette.systemOrange.assertRgb(255, 146, 48)
        DarkPalette.systemRed.assertRgb(255, 69, 58)
        DarkPalette.systemGreen.assertRgb(48, 209, 88)
    }
}

private fun Color.assertRgb(red: Int, green: Int, blue: Int, alpha: Float = 1f) {
    assertEquals(red, (this.red * 255).roundToInt())
    assertEquals(green, (this.green * 255).roundToInt())
    assertEquals(blue, (this.blue * 255).roundToInt())
    assertEquals(alpha, this.alpha, 0.011f)
}
