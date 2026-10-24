package com.shilapi.xcertplay

import android.os.Handler
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN")
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayConnectionStageTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var stage: TextView

    @Before fun setup() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        stage = TextView(activity)
        field("stageStatusView").set(activity, stage)
    }

    @After fun cleanup() {
        (field("mainHandler").get(activity) as Handler).removeCallbacksAndMessages(null)
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (field(name).get(activity) as ExecutorService).shutdownNow()
        }
    }

    @Test fun ignitionReadWaitIsVisibleInsteadOfTheGenericPreparationMessage() {
        showBlockedStage("waiting-ignition")

        assertEquals("正在读取车辆点火状态…", stage.text.toString())
        assertNotEquals(activity.getString(R.string.getting_carplay_ready), stage.text.toString())
    }

    @Test fun closingAndManualDisconnectKeepDifferentActionableMessages() {
        showBlockedStage("closing")
        assertEquals("正在关闭上一次连接…", stage.text.toString())

        showBlockedStage("manual-stop")
        assertEquals("连接已手动断开，请在主页点击连接。", stage.text.toString())
    }

    @Test fun confirmedIgnitionOffAndStoppedRetriesKeepTheirSpecificMessages() {
        showBlockedStage("ignition-off")
        assertEquals(activity.getString(R.string.carplay_vehicle_ignition_off), stage.text.toString())

        showBlockedStage("retry-stopped")
        assertEquals(activity.getString(R.string.wireless_startup_retries_exhausted), stage.text.toString())
    }

    private fun showBlockedStage(reason: String) {
        val message = activity.javaClass.getDeclaredMethod("startBlockStage", String::class.java)
            .apply { isAccessible = true }.invoke(activity, reason) as String
        activity.javaClass.getDeclaredMethod("setConnectionStage", String::class.java)
            .apply { isAccessible = true }.invoke(activity, message)
    }

    private fun field(name: String) = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }
}
