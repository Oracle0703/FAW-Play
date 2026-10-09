package com.shilapi.xcertplay

import android.view.View
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi", manifest = Config.NONE)
class FawSettingsPageTest {
    private lateinit var activity: DiPlayActivity
    private val tabs = listOf("连接设置", "画面显示", "导航与 HUD", "权限与帮助", "诊断", "关于")

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        FawSettingsPage.show(activity, "connection")
    }

    @Test fun connectionPageCombinesPairingAndConnectingAndOnlyExposesBootSwitch() {
        assertTrue(labels().containsAll(listOf("配对与连接", "选择 iPhone", "配对新手机", "连接手机", "断开连接")))
        assertEquals(listOf("车机启动后打开"), switches().map { it.text.toString() })
        assertTrue(switches().single().isChecked)
        switches().single().performClick()
        assertFalse(AirPlayPersistence.loadAutoStartOnBoot(activity))
        FawSettingsPage.show(activity, "connection")
        assertFalse(switches().single().isChecked)
        switches().single().performClick()
        assertTrue(AirPlayPersistence.loadAutoStartOnBoot(activity))
    }

    @Test fun frameRateChoicesSaveInlineWithoutOpeningADialog() {
        selectSettingsTab(activity, "画面显示")
        for (fps in listOf(50, 60, 30)) {
            settingsButton(activity, "$fps 帧").performClick()
            assertEquals(fps, FawSystemParameters.fps(activity))
            assertTrue(settingsButton(activity, "$fps 帧").isSelected)
            assertEquals(1, listOf(30, 50, 60).count { settingsButton(activity, "$it 帧").isSelected })
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
        }
        assertTrue(switches().isEmpty())
    }

    @Test fun navigationPageRetainsHudLocationAndHorizontalGestureInstructions() {
        selectSettingsTab(activity, "导航与 HUD")
        assertEquals(listOf("HUD 导航提示", "车机位置上报"), switches().map { it.text.toString() })
        assertTrue(labels().any { it.contains("CarPlay 导航时三指左滑开启仪表地图，右滑关闭") })
        assertFalse(labels().any { it == "仪表盘上的 CarPlay 地图" })
        val hud = switches().single { it.text.toString() == "HUD 导航提示" }
        assertTrue(hud.isChecked)
        hud.performClick()
        assertFalse(activity.getSharedPreferences("diplay_byd_outputs", 0).getBoolean("navigation_enabled", true))
    }

    @Test fun everyCurrentTabOmitsRemovedControlsAndVerticalScrolling() {
        val obsolete = listOf(R.string.hide_the_status_bar, R.string.hide_the_navigation_bar,
            R.string.auto_car_hotspot_title).map { activity.getString(it) }
        for (tab in tabs) {
            selectSettingsTab(activity, tab)
            val views = descendants(activity.window.decorView).toList()
            assertTrue(views.none { it is SeekBar || it is RadioButton || it is ScrollView })
            assertFalse(labels().any { it in obsolete })
            assertFalse(labels().any { it.contains("Wi-Fi Direct") || it.contains("USB 连接") || it.contains("手动信道") })
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
        }
    }

    @Test fun settingsStayFullscreenWithLegacyBarChoices() {
        AirPlayPersistence.saveHideTopBar(activity, false)
        AirPlayPersistence.saveHideBottomBar(activity, false)
        FawSettingsPage.show(activity, "connection")
        @Suppress("DEPRECATION")
        val flags = activity.window.decorView.systemUiVisibility
        @Suppress("DEPRECATION")
        assertTrue(flags and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
        @Suppress("DEPRECATION")
        assertTrue(flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
    }

    private fun labels() = descendants(activity.window.decorView).filterIsInstance<TextView>()
        .map { it.text.toString() }.toList()

    private fun switches() = descendants(activity.window.decorView).filterIsInstance<Switch>().toList()
}
