package com.shilapi.xcertplay

import android.app.Activity
import android.Manifest
import android.bluetooth.BluetoothManager
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.ParcelUuid
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBluetoothDevice
import com.shilapi.xcertplay.transport.IphoneBluetoothDevices
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi", manifest = Config.NONE)
class AutunnUiIntegrationTest {
    private val tabs = listOf("连接设置", "画面显示",
        if (com.shilapi.xcertplay.shared.BuildConfig.AUTUNN_NAVIGATION_OUTPUTS_ENABLED) "导航与 HUD" else "定位",
        "权限与帮助", "诊断", "授权", "关于")
    private val businessPreferences = listOf("diplay", "xcertplay_airplay", "diplay_byd_outputs", "carplay_picture")
    private lateinit var activity: DiPlaySettingsActivity

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        for (name in businessPreferences + "faw_ui") {
            context.getSharedPreferences(name, 0).edit().clear().apply()
        }
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(DiPlaySettingsActivity::class.java).get()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val phone = ShadowBluetoothDevice.newInstance("02:00:00:00:00:02")
        shadowOf(phone).setName("Test iPhone")
        shadowOf(phone).setUuids(arrayOf(ParcelUuid.fromString(IphoneBluetoothDevices.IAP2_SERVICE_UUID)))
        val adapter = activity.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setBondedDevices(setOf(phone))
        DiPlayPreferences.savePhone(activity, "02:00:00:00:00:02", "Test iPhone")
        activity.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putInt("media_audio_channel", 15).putBoolean("audio_focus_enabled", true).apply()
        activity.getSharedPreferences("diplay_byd_outputs", 0).edit()
            .putBoolean("navigation_enabled", false).apply()
    }

    @Test fun themeButtonKeepsTheCurrentSettingsTabAndSavedBusinessChoices() {
        assertEquals("system", AutunnUiTheme.mode(activity))
        val systemDark = AutunnUiTheme.isDark(activity)
        AutunnSettingsPage.show(activity, "connection")
        selectSettingsTab(activity, "画面显示")
        assertTrue(settingsButton(activity, "50 帧").performClick())
        val savedPreferences = snapshotBusinessPreferences()

        for (tab in tabs) {
            selectSettingsTab(activity, tab)
            for ((mode, dark) in themeCycle(systemDark)) {
                clickTheme(activity.window.decorView)
                assertEquals(mode, AutunnUiTheme.mode(activity))
                assertEquals(dark, AutunnUiTheme.isDark(activity))
                val root = taggedRoot(activity, "autunn_settings_root")
                assertThemeBackground(root, activity)
                assertTrue("Selected settings tab changed: $tab", settingsButton(activity, tab).isSelected)
                assertEquals(savedPreferences, snapshotBusinessPreferences())
                assertEquals(50, AutunnSystemParameters.fps(activity))
            }
        }
    }

    @Test fun homeThemeButtonKeepsTheHomePageAndSelectedPhone() {
        assertEquals("system", AutunnUiTheme.mode(activity))
        val systemDark = AutunnUiTheme.isDark(activity)
        AutunnSettingsPage.showHome(activity)
        val savedPreferences = snapshotBusinessPreferences()
        for ((mode, dark) in themeCycle(systemDark)) {
            clickTheme(activity.window.decorView)
            assertEquals(mode, AutunnUiTheme.mode(activity))
            assertEquals(dark, AutunnUiTheme.isDark(activity))
            val root = taggedRoot(activity, "autunn_home_root")
            assertThemeBackground(root, activity)
            assertTrue(descendants(root).filterIsInstance<TextView>().any { it.text.toString().contains("Test iPhone") })
            assertNotNull(root.findViewWithTag<View>("autunn_open_carplay"))
            assertEquals(savedPreferences, snapshotBusinessPreferences())
        }
    }

    @Test fun bothThemesKeepHomeAndEverySettingsControlInsideTheVehicleViewport() {
        for (dark in listOf(false, true)) {
            AutunnUiTheme.setDark(activity, dark)
            AutunnSettingsPage.showHome(activity)
            assertControlsFit(taggedRoot(activity, "autunn_home_root"), "home dark=$dark")
            AutunnSettingsPage.show(activity, "connection")
            for (tab in tabs) {
                selectSettingsTab(activity, tab)
                assertControlsFit(taggedRoot(activity, "autunn_settings_root"), "$tab dark=$dark")
            }
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w1920dp-h720dp-land-mdpi")
    fun englishPermissionStatusAndActionsFitInsideTheCardsPaddedContent() {
        assertEquals("en", activity.resources.configuration.locales[0].language)
        for (dark in listOf(false, true)) {
            AutunnUiTheme.setDark(activity, dark)
            AutunnSettingsPage.show(activity, "connection")
            selectSettingsTab(activity, "权限与帮助")
            val root = taggedRoot(activity, "autunn_settings_root")
            val page = "English permissions dark=$dark"
            assertControlsFit(root, page)

            val expectedStatus = PermissionAccess.status(activity)
            assertTrue("The English status must exercise both Bluetooth and Wi-Fi lines", expectedStatus.contains("\n"))
            val status = descendants(root).filterIsInstance<TextView>()
                .single { it.text.toString() == expectedStatus }
            assertTrue("The status must occupy at least two actual text lines", status.layout.lineCount >= 2)
            assertTextContentFits(status, page)
            assertInsidePaddedSurface(status, root, page, expectedStatus)

            val settings = settingsButton(activity, "应用权限")
            val card = checkNotNull(nearestSurface(settings, root))
            val bounds = Rect(0, 0, settings.width, settings.height)
            card.offsetDescendantRectToMyCoords(settings, bounds)
            assertTrue("The bottom permission action must leave the card's bottom padding intact",
                bounds.bottom <= card.height - card.paddingBottom)
        }
    }

    @Test fun preparationThemeChangesRetainLiveStatusRetryAndRecoveryViewsAndListeners() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            val root = CarPlayHostActivity::class.java.getDeclaredMethod("buildContentView")
                .apply { isAccessible = true }.invoke(host) as FrameLayout
            val stage = hostField(host, "stageStatusView") as TextView
            val retry = hostField(host, "startupRetryButton") as Button
            val recovery = hostField(host, "wifiRecoveryButton") as Button
            val message = "连接暂时中断，请重试"
            stage.text = message
            CarPlayHostActivity::class.java.getDeclaredField("latestStage")
                .apply { isAccessible = true }.set(host, message)
            retry.visibility = View.VISIBLE
            recovery.visibility = View.VISIBLE
            var retryClicks = 0
            var recoveryClicks = 0
            retry.setOnClickListener { retryClicks++ }
            recovery.setOnClickListener { recoveryClicks++ }
            AutunnSettingsPage.stylePreparation(host)
            assertEquals("system", AutunnUiTheme.mode(host))
            val systemDark = AutunnUiTheme.isDark(host)
            val savedPreferences = snapshotBusinessPreferences()

            for ((index, choice) in themeCycle(systemDark).withIndex()) {
                val (mode, dark) = choice
                val before = root.findViewWithTag<ViewGroup>("autunn_connection_preparation")
                clickTheme(before)
                val after = root.findViewWithTag<ViewGroup>("autunn_connection_preparation")
                assertNotSame("Preparation must apply the newly chosen colors", before, after)
                assertEquals(mode, AutunnUiTheme.mode(host))
                assertEquals(dark, AutunnUiTheme.isDark(host))
                assertThemeBackground(after, host)
                assertSame(stage, hostField(host, "stageStatusView"))
                assertSame(retry, hostField(host, "startupRetryButton"))
                assertSame(recovery, hostField(host, "wifiRecoveryButton"))
                assertTrue(descendants(after).any { it === stage })
                assertTrue(descendants(after).any { it === retry })
                assertTrue(descendants(after).any { it === recovery })
                assertEquals(message, stage.text.toString())
                assertEquals(message, hostField(host, "latestStage"))
                assertEquals(View.VISIBLE, retry.visibility)
                assertEquals(View.VISIBLE, recovery.visibility)
                assertTrue(retry.performClick())
                assertTrue(recovery.performClick())
                assertEquals(index + 1, retryClicks)
                assertEquals(index + 1, recoveryClicks)
                assertEquals(savedPreferences, snapshotBusinessPreferences())
                assertControlsFit(after, "preparation mode=$mode")
            }
        } finally {
            closeHost(host)
        }
    }

    @Test fun automaticThemeRestylingKeepsThePreparationHiddenWhileCarPlayIsVisible() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            AutunnUiTheme.setDark(host, false)
            val root = CarPlayHostActivity::class.java.getDeclaredMethod("buildContentView")
                .apply { isAccessible = true }.invoke(host) as FrameLayout
            val stage = hostField(host, "stageStatusView") as TextView
            val retry = hostField(host, "startupRetryButton") as Button
            val recovery = hostField(host, "wifiRecoveryButton") as Button
            stage.text = "CarPlay 已连接"
            AutunnSettingsPage.stylePreparation(host)
            var preparation = root.findViewWithTag<ViewGroup>("autunn_connection_preparation")
            preparation.visibility = View.GONE

            for (dark in listOf(true, false, true)) {
                AutunnUiTheme.setDark(host, dark)
                AutunnSettingsPage.stylePreparation(host)
                val replacement = root.findViewWithTag<ViewGroup>("autunn_connection_preparation")
                assertNotSame(preparation, replacement)
                assertEquals("Appearance changes must not cover the active projection", View.GONE, replacement.visibility)
                assertSame(replacement, hostField(host, "connectionPanel"))
                assertThemeBackground(replacement, host)
                assertSame(stage, hostField(host, "stageStatusView"))
                assertSame(retry, hostField(host, "startupRetryButton"))
                assertSame(recovery, hostField(host, "wifiRecoveryButton"))
                assertTrue(descendants(replacement).any { it === stage })
                assertTrue(descendants(replacement).any { it === retry })
                assertTrue(descendants(replacement).any { it === recovery })
                assertEquals("CarPlay 已连接", stage.text.toString())
                preparation = replacement
            }
            preparation.visibility = View.VISIBLE
            AutunnSettingsPage.stylePreparation(host)
            assertSame(preparation, root.findViewWithTag<ViewGroup>("autunn_connection_preparation"))
            assertEquals(View.VISIBLE, preparation.visibility)
            assertThemeBackground(preparation, host)
            assertControlsFit(preparation, "preparation restored after appearance changes")
        } finally {
            closeHost(host)
        }
    }

    private fun taggedRoot(activity: Activity, tag: String): ViewGroup =
        descendants(activity.window.decorView).single { it.tag == tag } as ViewGroup

    private fun themeCycle(systemDark: Boolean) =
        listOf("light" to false, "dark" to true, "system" to systemDark)

    private fun clickTheme(root: View) {
        val toggle = descendants(root).single { it.tag == "autunn_theme_toggle" }
        assertTrue("Theme control is clickable", toggle.performClick())
    }

    private fun snapshotBusinessPreferences() = businessPreferences.associateWith { name ->
        activity.getSharedPreferences(name, 0).all.toMap()
    }

    private fun assertThemeBackground(root: View, context: Activity) {
        assertEquals(AutunnUiTheme.palette(context).background, (root.background as ColorDrawable).color)
    }

    private fun assertControlsFit(root: ViewGroup, page: String) {
        val width = 1920
        val height = 720
        repeat(3) {
            descendants(root).forEach(View::forceLayout)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
        }
        val controls = descendants(root).filter { it is Button || it is Switch }
            .filter { visibleWithin(it, root) }.toList()
        assertTrue("No visible controls on $page", controls.isNotEmpty())
        for (control in controls) {
            val text = control as TextView
            val label = text.text.toString()
            assertTrue("$page / $label has no width", control.width > 0)
            assertTrue("$page / $label has no height", control.height > 0)
            val bounds = Rect(0, 0, control.width, control.height)
            root.offsetDescendantRectToMyCoords(control, bounds)
            assertTrue("$page / $label is outside the viewport: $bounds",
                bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height)
            assertInsidePaddedSurface(control, root, page, label)
            assertTextContentFits(text, page)
        }
    }

    private fun assertInsidePaddedSurface(view: View, root: View, page: String, label: String) {
        nearestSurface(view, root)?.let { surface ->
            val bounds = Rect(0, 0, view.width, view.height)
            surface.offsetDescendantRectToMyCoords(view, bounds)
            assertTrue("$page / $label is outside its card's padded content: $bounds card=${surface.width}x${surface.height}",
                bounds.left >= surface.paddingLeft && bounds.top >= surface.paddingTop &&
                    bounds.right <= surface.width - surface.paddingRight &&
                    bounds.bottom <= surface.height - surface.paddingBottom)
        }
    }

    private fun assertTextContentFits(text: TextView, page: String) {
        val layout = checkNotNull(text.layout) { "$page / ${text.text} has no text layout" }
        val availableHeight = text.height - text.compoundPaddingTop - text.compoundPaddingBottom
        assertTrue("$page / ${text.text} clips its text: text=${layout.height} available=$availableHeight",
            layout.height <= availableHeight)
    }

    private fun nearestSurface(control: View, root: View): ViewGroup? {
        var parent = control.parent as? View
        while (parent != null && parent !== root) {
            if (parent is ViewGroup && parent.background is GradientDrawable) return parent
            parent = parent.parent as? View
        }
        return null
    }

    private fun visibleWithin(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun hostField(host: CarPlayHostActivity, name: String): Any? =
        CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(host)

    private fun closeHost(host: CarPlayHostActivity) {
        (hostField(host, "mainHandler") as Handler).removeCallbacksAndMessages(null)
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (hostField(host, name) as ExecutorService).shutdownNow()
        }
    }
}
