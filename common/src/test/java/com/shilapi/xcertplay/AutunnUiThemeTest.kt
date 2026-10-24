package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class AutunnUiThemeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("faw_ui", Context.MODE_PRIVATE)

    @Before fun clearAppearance() {
        preferences.edit().clear().apply()
    }

    @Test fun freshInstallationFollowsTheVehicleUiModeAutomatically() {
        assertEquals("system", AutunnUiTheme.mode(context))
        assertFalse(AutunnUiTheme.palette(configuredContext(Configuration.UI_MODE_NIGHT_NO)).dark)
        assertTrue(AutunnUiTheme.palette(configuredContext(Configuration.UI_MODE_NIGHT_YES)).dark)
    }

    @Test fun undefinedSignalsRetainTheLastAutomaticChoiceWithADarkFallback() {
        val configuration = context.resources.configuration
        val originalUiMode = configuration.uiMode
        fun report(nightMode: Int) {
            // A zero override inherits the base configuration; mutate the reported value directly.
            configuration.uiMode = Configuration.UI_MODE_TYPE_CAR or nightMode
            assertEquals(nightMode, context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        }
        try {
            report(Configuration.UI_MODE_NIGHT_UNDEFINED)
            assertTrue(AutunnUiTheme.isDark(context))
            report(Configuration.UI_MODE_NIGHT_NO)
            assertFalse(AutunnUiTheme.isDark(context))
            report(Configuration.UI_MODE_NIGHT_UNDEFINED)
            assertFalse(AutunnUiTheme.isDark(context))
            report(Configuration.UI_MODE_NIGHT_YES)
            assertTrue(AutunnUiTheme.isDark(context))
            report(Configuration.UI_MODE_NIGHT_UNDEFINED)
            assertTrue(AutunnUiTheme.isDark(context))
        } finally {
            configuration.uiMode = originalUiMode
        }
    }

    @Test fun legacyManualChoicesRemainSavedButCanReturnToAutomatic() {
        preferences.edit().putBoolean("dark", false).apply()
        assertEquals("light", AutunnUiTheme.mode(context))
        assertFalse(AutunnUiTheme.isDark(configuredContext(Configuration.UI_MODE_NIGHT_YES)))
        AutunnUiTheme.setMode(context, "system")
        assertEquals("system", AutunnUiTheme.mode(context))
        assertTrue(AutunnUiTheme.isDark(configuredContext(Configuration.UI_MODE_NIGHT_YES)))
        assertFalse(preferences.contains("dark"))
    }

    @Test fun invalidModesDoNotOverwriteTheSavedChoice() {
        AutunnUiTheme.setMode(context, "light")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AutunnUiTheme.setMode(context, "unexpected")
        }
        assertEquals("light", AutunnUiTheme.mode(context))
        preferences.edit().putString("mode", "unexpected").apply()
        assertEquals("system", AutunnUiTheme.mode(context))
    }

    @Test fun savedChoiceIsReadByNewActivities() {
        val firstActivity = Robolectric.buildActivity(Activity::class.java).get()
        AutunnUiTheme.setDark(firstActivity, false)
        val secondActivity = Robolectric.buildActivity(Activity::class.java).get()
        assertFalse(AutunnUiTheme.isDark(secondActivity))
        assertFalse(AutunnUiTheme.palette(secondActivity).dark)

        AutunnUiTheme.setDark(secondActivity, true)
        val thirdActivity = Robolectric.buildActivity(Activity::class.java).get()
        assertTrue(AutunnUiTheme.isDark(thirdActivity))
        assertTrue(AutunnUiTheme.palette(firstActivity).dark)
    }

    @Test fun manualAppearanceOverridesSystemOrCarPlayNightConfiguration() {
        val dayContext = configuredContext(Configuration.UI_MODE_NIGHT_NO)
        val nightContext = configuredContext(Configuration.UI_MODE_NIGHT_YES)
        AutunnUiTheme.setDark(dayContext, false)
        assertFalse(AutunnUiTheme.palette(dayContext).dark)
        assertFalse(AutunnUiTheme.palette(nightContext).dark)
        AutunnUiTheme.setDark(nightContext, true)
        assertTrue(AutunnUiTheme.palette(dayContext).dark)
        assertTrue(AutunnUiTheme.palette(nightContext).dark)
    }

    @Test fun appearanceChangesPreserveConnectionAndOtherPreferences() {
        val connection = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
        val projection = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        connection.edit().putString("phone_address", "test-device").apply()
        projection.edit().putString("carplay_night_mode", "night")
            .putInt("media_audio_channel", 15).apply()
        preferences.edit().putString("unrelated", "retained").apply()
        val connectionBefore = connection.all
        val projectionBefore = projection.all

        AutunnUiTheme.setDark(context, false)
        AutunnUiTheme.setDark(context, true)

        assertEquals(connectionBefore, connection.all)
        assertEquals(projectionBefore, projection.all)
        assertEquals("retained", preferences.getString("unrelated", null))
    }

    @Test fun lightAndDarkPalettesMatchTheApprovedDesign() {
        AutunnUiTheme.setDark(context, false)
        assertPalette(AutunnUiTheme.palette(context), listOf(
            "#F1DDDF", "#FCF6F7", "#E2CCD0", "#262125",
            "#776B72", "#E72D48", "#FCE5E9", "#FFFFFF",
        ))
        AutunnUiTheme.setDark(context, true)
        assertPalette(AutunnUiTheme.palette(context), listOf(
            "#1A1A1D", "#262629", "#38383D", "#F4F4F6",
            "#AFADB5", "#1DDFFF", "#263C42", "#1A1A1D",
        ))
    }

    @Test fun darkPrimaryButtonsHaveReadableTextOnCyan() {
        AutunnUiTheme.setMode(context, "dark")
        val palette = AutunnUiTheme.palette(context)
        val contrast = (luminance(palette.accent) + 0.05) /
            (luminance(palette.onAccent) + 0.05)
        assertTrue("Primary button contrast is $contrast", contrast >= 4.5)
    }

    private fun configuredContext(nightMode: Int): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode = nightMode or Configuration.UI_MODE_TYPE_CAR
        return context.createConfigurationContext(configuration)
    }

    private fun assertPalette(palette: AutunnUiTheme.Palette, expected: List<String>) {
        assertEquals(expected.map(Color::parseColor), listOf(
            palette.background, palette.surface, palette.border, palette.text,
            palette.muted, palette.accent, palette.selected, palette.onAccent,
        ))
    }

    private fun luminance(color: Int): Double {
        fun linear(component: Int): Double {
            val srgb = component / 255.0
            return if (srgb <= 0.04045) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(Color.red(color)) +
            0.7152 * linear(Color.green(color)) + 0.0722 * linear(Color.blue(color))
    }
}
