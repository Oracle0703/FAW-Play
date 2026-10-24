package com.shilapi.xcertplay

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.view.Display
import android.widget.TextView
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE, qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
class HongqiDefaultClusterLayoutTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("xcertplay_airplay", 0)
    private var host: CarPlayHostActivity? = null
    private var displayId: Int? = null

    @Before fun setup() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        prefs.edit().clear().commit()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        ClusterLayoutTuning.preferences(app).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.overlaySettingsListener = null
        AirPlayPersistence.saveClusterMapEnabled(app, true)
        ShadowSettings.setCanDrawOverlays(false)
    }

    @After fun cleanup() {
        host?.let { activity ->
            field(activity, "mainHandler").let { (it as Handler).removeCallbacksAndMessages(null) }
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                (field(activity, name) as ExecutorService).shutdownNow()
            }
        }
        displayId?.let(ShadowDisplayManager::removeDisplay)
        ShadowSettings.setCanDrawOverlays(false)
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.overlaySettingsListener = null
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
    }

    @Test fun navigationSettingsHaveNoExperimentalLayoutEntryOrChoices() {
        prefs.edit().putString("cluster_maneuver_layout", "TOP_ALIGNED").commit()
        val activity = Robolectric.buildActivity(DiPlaySettingsActivity::class.java).get()
        AutunnSettingsPage.show(activity, "connection")
        selectSettingsTab(activity, "导航与 HUD")
        val labels = descendants(activity.window.decorView).filterIsInstance<TextView>()
            .map { it.text.toString() }.toList()
        assertTrue(labels.contains("HUD 导航提示"))
        assertTrue(labels.any { it.contains("三指左滑开启仪表地图") })
        for (removed in listOf("仪表导航卡布局", "顶部（测试）", "左侧（测试）", "右侧（测试）", "恢复原布局")) {
            assertFalse("Removed option must not be available: $removed", labels.any { it.contains(removed) })
        }
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test @Config(sdk = [28]) fun oldLayoutPreferencesCannotChangeThePhysicalInstrumentConfiguration() {
        ShadowSettings.setCanDrawOverlays(true)
        val id = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi", 5)
        displayId = id
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(display).setName("Cluster-Display")
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        assertEquals(id, ClusterMapPresentation.findDisplay(app)?.displayId)
        val size = ClusterMapPresentation.sizeOf(display)
        assertEquals(1920, size.x)
        assertEquals(720, size.y)
        assertOldChoicesAreIgnored(physical = true)
    }

    @Test fun oldLayoutPreferencesCannotChangeTheVirtualInstrumentConfiguration() {
        assertOldChoicesAreIgnored(physical = false)
    }

    private fun assertOldChoicesAreIgnored(physical: Boolean) {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        host = activity
        val placement = ClusterLayoutTuning.Placement(90, 622, 80, 450, 45)
        ClusterLayoutTuning.save(app, ClusterLayoutTuning.Target.ETE, placement)
        val paired = app.getSharedPreferences("diplay", 0)
        paired.edit().putString("phone_address", "00:00:00:00:00:01")
            .putString("phone_name", "Test phone").commit()
        AirPlayPersistence.saveClusterMapScalePercent(app, 67)
        AirPlayPersistence.saveClusterMarkerHorizontalStep(app, 1)
        AirPlayPersistence.saveClusterMarkerVerticalStep(app, -1)
        for (content in Content.entries) {
            prefs.edit().remove("cluster_maneuver_layout").commit()
            AirPlayPersistence.saveClusterContent(app, content)
            val baseline = config(activity)
            assertEquals(content.url, baseline.initialUrl)
            if (physical) {
                assertEquals(1280, baseline.widthPixels)
                assertEquals(480, baseline.heightPixels)
            }
            for (oldChoice in listOf("TOP_ALIGNED", "LEFT_ALIGNED", "RIGHT_ALIGNED", "ORIGINAL", "invalid", 7)) {
                prefs.edit().apply {
                    if (oldChoice is String) putString("cluster_maneuver_layout", oldChoice)
                    else putInt("cluster_maneuver_layout", oldChoice as Int)
                }.commit()
                val before = prefs.all.toMap()
                assertEquals("Old preference $oldChoice must not affect $content", baseline, config(activity))
                assertEquals(before, prefs.all)
                assertEquals(content, AirPlayPersistence.loadClusterContent(app))
                assertEquals(placement, ClusterLayoutTuning.load(app, ClusterLayoutTuning.Target.ETE))
                assertEquals("00:00:00:00:00:01", paired.getString("phone_address", null))
                assertEquals("Test phone", paired.getString("phone_name", null))
            }
        }
    }

    private fun config(activity: CarPlayHostActivity): AirPlayDisplayConfig =
        CarPlayHostActivity::class.java.getDeclaredMethod("clusterDisplayConfig")
            .apply { isAccessible = true }.invoke(activity) as AirPlayDisplayConfig

    private fun field(activity: CarPlayHostActivity, name: String): Any =
        CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
}
