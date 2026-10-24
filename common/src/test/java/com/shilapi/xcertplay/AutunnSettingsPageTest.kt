package com.shilapi.xcertplay

import android.content.Intent
import android.Manifest
import android.bluetooth.BluetoothManager
import android.os.ParcelUuid
import android.view.View
import android.view.WindowManager
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBluetoothDevice
import com.shilapi.xcertplay.transport.IphoneBluetoothDevices

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi", manifest = Config.NONE)
class AutunnSettingsPageTest {
    private lateinit var activity: DiPlaySettingsActivity
    private val tabs = listOf("连接设置", "画面显示", "导航与 HUD", "权限与帮助", "诊断", "授权", "关于")

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(DiPlaySettingsActivity::class.java).get()
        AutunnSettingsPage.show(activity, "connection")
    }

    @Test fun connectionPageCombinesPairingAndConnectingAndOnlyExposesBootSwitch() {
        assertTrue(labels().containsAll(listOf("配对与连接", "选择 iPhone", "配对新手机", "连接手机", "断开连接")))
        assertEquals(listOf("车机启动后打开"), switches().map { it.text.toString() })
        assertTrue(switches().single().isChecked)
        switches().single().performClick()
        assertFalse(AirPlayPersistence.loadAutoStartOnBoot(activity))
        AutunnSettingsPage.show(activity, "connection")
        assertFalse(switches().single().isChecked)
        switches().single().performClick()
        assertTrue(AirPlayPersistence.loadAutoStartOnBoot(activity))
    }

    @Test fun frameRateChoicesSaveInlineWithoutOpeningADialog() {
        selectSettingsTab(activity, "画面显示")
        for (fps in listOf(50, 60, 30)) {
            settingsButton(activity, "$fps 帧").performClick()
            assertEquals(fps, AutunnSystemParameters.fps(activity))
            assertTrue(settingsButton(activity, "$fps 帧").isSelected)
            assertEquals(1, listOf(30, 50, 60).count { settingsButton(activity, "$it 帧").isSelected })
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
        }
        assertTrue(switches().isEmpty())
    }

    @Test fun connectionChoicesShowRenamedIphonesAndOmitOtherPairedDevices() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val renamed = ShadowBluetoothDevice.newInstance("10:00:00:00:00:01")
        val android = ShadowBluetoothDevice.newInstance("10:00:00:00:00:02")
        val speaker = ShadowBluetoothDevice.newInstance("10:00:00:00:00:03")
        shadowOf(renamed).setName("家里的手机")
        shadowOf(renamed).setUuids(arrayOf(ParcelUuid.fromString(IphoneBluetoothDevices.IAP2_SERVICE_UUID)))
        shadowOf(android).setName("Android 测试手机")
        shadowOf(speaker).setName("蓝牙音箱")
        val adapter = activity.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setBondedDevices(setOf(renamed, android, speaker))
        AutunnSettingsPage.show(activity, "connection")
        assertTrue(labels().contains("家里的手机"))
        assertFalse(labels().any { it.contains("Android 测试手机") || it.contains("蓝牙音箱") })
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

    @Test fun vehicleStateIsAnExplicitReadOnlyDiagnosticEntry() {
        selectSettingsTab(activity, "诊断")
        assertNotNull(settingsButton(activity, "车辆状态（只读）"))
        assertNotNull(settingsButton(activity, "空调与座椅"))
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
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
        AutunnSettingsPage.show(activity, "connection")
        @Suppress("DEPRECATION")
        val flags = activity.window.decorView.systemUiVisibility
        @Suppress("DEPRECATION")
        assertTrue(flags and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
        @Suppress("DEPRECATION")
        assertTrue(flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
    }

    @Test fun choiceDialogOnSettingsRemainsVisibleAndDeliversSelection() {
        var selected = -1
        val dialog = LandscapeSettingsLayout.dialogBuilder(activity)
            .setTitle("选择")
            .setSingleChoiceItems(arrayOf("第一项", "第二项", "第三项"), 0) { dialog, which ->
                selected = which
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        assertTrue(dialog.isShowing)
        assertEquals(1f, dialog.window!!.attributes.alpha)
        assertEquals(0, dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        descendants(dialog.window!!.decorView).filterIsInstance<RadioButton>()
            .single { it.text.toString() == "第二项" }.performClick()
        assertEquals(1, selected)
        assertFalse(dialog.isShowing)
        assertTrue(labels().contains("配对与连接"))
    }

    @Test fun pageIntentsAndHomeNavigationUseCurrentRenderer() {
        activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }.invoke(activity, Intent().putExtra("page", "about"))
        assertTrue(labels().contains("语言"))
        assertTrue(settingsButton(activity, "关于").isSelected)
        assertFalse(labels().contains("配对与连接"))
        activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }.invoke(activity, Intent().putExtra("page", "authorization"))
        assertTrue(settingsButton(activity, "授权").isSelected)
        assertNotNull(activity.window.decorView.findViewWithTag<View>("autunn_authorization_panel"))
        assertFalse(labels().contains("语言"))
        settingsButton(activity, "返回首页").performClick()
        assertNotNull(activity.window.decorView.findViewWithTag<View>("autunn_home_root"))
        settingsButton(activity, "设置").performClick()
        assertTrue(labels().contains("配对与连接"))
        assertTrue(descendants(activity.window.decorView).none { it is ScrollView })
    }

    private fun labels() = descendants(activity.window.decorView).filterIsInstance<TextView>()
        .map { it.text.toString() }.toList()

    private fun switches() = descendants(activity.window.decorView).filterIsInstance<Switch>().toList()
}
