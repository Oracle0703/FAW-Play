package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.hud.HongqiVehicleClimateSwitch
import com.shilapi.xcertplay.hud.HongqiVehicleCirculation
import com.shilapi.xcertplay.hud.HongqiVehicleConnectionState
import com.shilapi.xcertplay.hud.HongqiVehicleControlCommand
import com.shilapi.xcertplay.hud.HongqiVehicleControlResult
import com.shilapi.xcertplay.hud.HongqiVehicleControlStatus
import com.shilapi.xcertplay.hud.HongqiVehicleField
import com.shilapi.xcertplay.hud.HongqiVehicleReading
import com.shilapi.xcertplay.hud.HongqiVehicleSeatLevel
import com.shilapi.xcertplay.hud.HongqiVehicleSide
import com.shilapi.xcertplay.hud.HongqiVehicleSnapshot
import java.io.Closeable
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class HongqiVehicleControlDialogTest {
    private data class Invocation(
        val command: HongqiVehicleControlCommand,
        val callback: (HongqiVehicleControlResult) -> Unit,
    )

    private lateinit var host: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var current: HongqiVehicleSnapshot
    private var shown: AlertDialog? = null
    private var acquired = 0
    private var released = 0
    private var samples = 0
    private var requestsClosed = 0
    private val invocations = mutableListOf<Invocation>()

    @Before fun setUp() {
        host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        activity = host.get()
        current = available()
    }

    @After fun tearDown() {
        shown?.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        if (!activity.isDestroyed) host.pause().stop().destroy()
    }

    @Test fun openingAndRefreshingNeverSubmitAndTheVisibleDialogOwnsOneLease() {
        val session = session()
        val dialog = session.show().also { shown = it }
        session.show()
        assertEquals(1, acquired)
        assertTrue(actions(dialog).all { it.isEnabled })
        assertTrue(views(dialog.window!!.decorView).any { it is ScrollView })
        val initialSamples = samples
        update(HongqiVehicleField.FAN, 3.0)
        // Observe a real refresh instead of counting tasks exactly on a virtual-clock boundary.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))
        assertTrue("The visible dialog must read a later snapshot", samples > initialSamples)
        assertEquals("当前：3 档", value(dialog, HongqiVehicleField.FAN))
        assertTrue(invocations.isEmpty())
        dialog.dismiss()
        // Dialog dispatches OnDismissListener through its Handler.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, released)
        val lastSamples = samples
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(lastSamples, samples)
        assertTrue(invocations.isEmpty())
    }

    @Test fun aClickSubmitsOnceAndNeitherAnAcknowledgementNorWaitingChangesDisplayedState() {
        val dialog = show()
        button(dialog, HongqiVehicleField.DRIVER_TEMPERATURE, 1).performClick()
        assertEquals(HongqiVehicleControlCommand.Temperature(HongqiVehicleSide.DRIVER, 25.5f), invocations.single().command)
        assertEquals("当前：25.0 °C", value(dialog, HongqiVehicleField.DRIVER_TEMPERATURE))
        assertTrue(actions(dialog).none { it.isEnabled })
        answer(0, HongqiVehicleControlStatus.ACCEPTED_UNCONFIRMED)
        assertEquals("请求已提交，等待车辆反馈…", outcome(dialog))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(1, invocations.size)
        assertEquals("当前：25.0 °C", value(dialog, HongqiVehicleField.DRIVER_TEMPERATURE))

        update(HongqiVehicleField.DRIVER_TEMPERATURE, 25.5)
        answer(0, HongqiVehicleControlStatus.CONFIRMED)
        assertEquals("反馈已确认", outcome(dialog))
        assertEquals("当前：25.5 °C", value(dialog, HongqiVehicleField.DRIVER_TEMPERATURE))
        assertTrue(button(dialog, HongqiVehicleField.DRIVER_TEMPERATURE, 1).isEnabled)
        assertEquals(1, requestsClosed)
    }

    @Test fun everyVisibleControlCreatesItsTypedSideAndValueWithoutRawWireCommands() {
        val dialog = show()
        val cases = listOf(
            Triple(HongqiVehicleField.PASSENGER_TEMPERATURE, 0,
                HongqiVehicleControlCommand.Temperature(HongqiVehicleSide.PASSENGER, 25.5f)),
            Triple(HongqiVehicleField.FAN, 1, HongqiVehicleControlCommand.Fan(3)),
            Triple(HongqiVehicleField.CLIMATE_POWER, 0,
                HongqiVehicleControlCommand.Switch(HongqiVehicleClimateSwitch.POWER, false)),
            Triple(HongqiVehicleField.CLIMATE_AUTO, 1,
                HongqiVehicleControlCommand.Switch(HongqiVehicleClimateSwitch.AUTO, true)),
            Triple(HongqiVehicleField.CLIMATE_COMPRESSOR, 1,
                HongqiVehicleControlCommand.Switch(HongqiVehicleClimateSwitch.COMPRESSOR, true)),
            Triple(HongqiVehicleField.CLIMATE_SYNC, 1,
                HongqiVehicleControlCommand.Switch(HongqiVehicleClimateSwitch.SYNC, true)),
            Triple(HongqiVehicleField.CLIMATE_CIRCULATION, 1,
                HongqiVehicleControlCommand.Circulation(HongqiVehicleCirculation.RECIRCULATE)),
            Triple(HongqiVehicleField.CLIMATE_FRONT_DEFROST, 1,
                HongqiVehicleControlCommand.Switch(HongqiVehicleClimateSwitch.FRONT_DEFROST, true)),
            Triple(HongqiVehicleField.DRIVER_HEAT, 3,
                HongqiVehicleControlCommand.SeatHeat(HongqiVehicleSide.DRIVER, HongqiVehicleSeatLevel.HIGH)),
            Triple(HongqiVehicleField.PASSENGER_HEAT, 2,
                HongqiVehicleControlCommand.SeatHeat(HongqiVehicleSide.PASSENGER, HongqiVehicleSeatLevel.MEDIUM)),
            Triple(HongqiVehicleField.DRIVER_VENT, 1,
                HongqiVehicleControlCommand.SeatVent(HongqiVehicleSide.DRIVER, HongqiVehicleSeatLevel.LOW)),
            Triple(HongqiVehicleField.PASSENGER_VENT, 0,
                HongqiVehicleControlCommand.SeatVent(HongqiVehicleSide.PASSENGER, HongqiVehicleSeatLevel.OFF)),
        )
        for ((field, index, expected) in cases) {
            button(dialog, field, index).performClick()
            assertEquals(expected, invocations.last().command)
            answer(invocations.lastIndex, HongqiVehicleControlStatus.REJECTED)
        }
        assertEquals(cases.size, invocations.size)
    }

    @Test fun staleMissingInvalidAndUnsupportedValuesDisableOnlyTheirControls() {
        val now = SystemClock.elapsedRealtime()
        current = current.copy(readings = current.readings + mapOf(
            HongqiVehicleField.DRIVER_TEMPERATURE to HongqiVehicleReading(25.0, now - 6_001),
            HongqiVehicleField.PASSENGER_TEMPERATURE to HongqiVehicleReading(Double.NaN, now),
            HongqiVehicleField.CLIMATE_AUTO to HongqiVehicleReading(1.0, now + 60_000),
            HongqiVehicleField.CLIMATE_CIRCULATION to HongqiVehicleReading(0.0, now),
            HongqiVehicleField.FRONT_HEAT_SUPPORTED to HongqiVehicleReading(0.0, now),
            HongqiVehicleField.FRONT_VENT_SUPPORTED to HongqiVehicleReading(null, now),
        ))
        val dialog = show()
        for (field in listOf(HongqiVehicleField.DRIVER_TEMPERATURE, HongqiVehicleField.PASSENGER_TEMPERATURE,
            HongqiVehicleField.CLIMATE_AUTO, HongqiVehicleField.CLIMATE_CIRCULATION,
            HongqiVehicleField.DRIVER_HEAT, HongqiVehicleField.PASSENGER_HEAT,
            HongqiVehicleField.DRIVER_VENT, HongqiVehicleField.PASSENGER_VENT)) {
            assertFalse("$field must be disabled", button(dialog, field, 0).isEnabled)
            button(dialog, field, 0).performClick()
        }
        assertTrue(invocations.isEmpty())
        assertTrue(button(dialog, HongqiVehicleField.FAN, 1).isEnabled)
        assertEquals("当前配置不支持", value(dialog, HongqiVehicleField.DRIVER_HEAT))
        assertEquals("配置未知", value(dialog, HongqiVehicleField.DRIVER_VENT))
    }

    @Test fun clickingRechecksSnapshotEvenBeforeTheNextVisualRefresh() {
        val dialog = show()
        assertTrue(button(dialog, HongqiVehicleField.FAN, 1).isEnabled)
        current = current.copy(status = HongqiVehicleConnectionState.UNAVAILABLE)
        button(dialog, HongqiVehicleField.FAN, 1).performClick()
        assertTrue(invocations.isEmpty())
        assertTrue(actions(dialog).none { it.isEnabled })
        assertEquals("当前：未知", value(dialog, HongqiVehicleField.FAN))
    }

    @Test fun timeoutIsUnconfirmedAndNeverRetriesOrWritesTheRequestedValueIntoTheDisplay() {
        val dialog = show()
        button(dialog, HongqiVehicleField.FAN, 1).performClick()
        answer(0, HongqiVehicleControlStatus.TIMED_OUT)
        assertTrue(outcome(dialog).contains("结果未确认"))
        assertEquals("当前：2 档", value(dialog, HongqiVehicleField.FAN))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertEquals(1, invocations.size)
        assertTrue(actions(dialog).none { it.isEnabled })
    }

    @Test fun cancellationRetiresTheHandleAndLateCallbacksCannotCompleteANewerRequest() {
        val dialog = show()
        button(dialog, HongqiVehicleField.FAN, 1).performClick()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        assertTrue(dialog.isShowing)
        assertEquals(1, requestsClosed)
        assertTrue(outcome(dialog).contains("结果未确认"))
        assertFalse(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, invocations.size)
        button(dialog, HongqiVehicleField.FAN, 0).performClick()
        answer(0, HongqiVehicleControlStatus.CONFIRMED)
        assertEquals("正在提交，等待车辆反馈…", outcome(dialog))
        assertTrue(actions(dialog).none { it.isEnabled })
        answer(1, HongqiVehicleControlStatus.REJECTED)
        assertTrue(outcome(dialog).contains("未被接受"))
        assertEquals(2, requestsClosed)
        assertEquals(2, invocations.size)
    }

    @Test fun stoppingTheActivityClosesPendingWorkAndLeaseExactlyOnceAndStopsPolling() {
        val dialog = show()
        button(dialog, HongqiVehicleField.FAN, 1).performClick()
        host.pause().stop()
        assertFalse(dialog.isShowing)
        assertEquals(1, released)
        assertEquals(1, requestsClosed)
        val finalSamples = samples
        answer(0, HongqiVehicleControlStatus.CONFIRMED)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertEquals(finalSamples, samples)
        assertEquals(1, invocations.size)
        host.destroy()
        assertEquals(1, released)
        assertEquals(1, requestsClosed)
    }

    @Test fun synchronousCompletionDoesNotLeaveAnOrphanHandleOrDisableTheNextAction() {
        val dialog = HongqiVehicleControlDialog(activity,
            acquire = { Closeable { released++ } }, snapshot = { current },
            submit = { command, callback ->
                callback(HongqiVehicleControlResult(command, HongqiVehicleControlStatus.UNAVAILABLE))
                Closeable { requestsClosed++ }
            },
        ).show().also { shown = it }
        button(dialog, HongqiVehicleField.FAN, 1).performClick()
        assertEquals(1, requestsClosed)
        assertTrue(button(dialog, HongqiVehicleField.FAN, 1).isEnabled)
        assertTrue(outcome(dialog).contains("结果未确认"))
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, requestsClosed)
        assertEquals(1, released)
    }

    @Test fun temperatureLimitsAndCompressorFeedbackTwoKeepTheirDistinctMeanings() {
        update(HongqiVehicleField.DRIVER_TEMPERATURE, 17.5)
        update(HongqiVehicleField.PASSENGER_TEMPERATURE, 32.5)
        update(HongqiVehicleField.CLIMATE_COMPRESSOR, 2.0)
        update(HongqiVehicleField.CLIMATE_AUTO, 2.0)
        val dialog = show()
        assertFalse(button(dialog, HongqiVehicleField.DRIVER_TEMPERATURE, 0).isEnabled)
        assertTrue(button(dialog, HongqiVehicleField.DRIVER_TEMPERATURE, 1).isEnabled)
        assertTrue(button(dialog, HongqiVehicleField.PASSENGER_TEMPERATURE, 0).isEnabled)
        assertFalse(button(dialog, HongqiVehicleField.PASSENGER_TEMPERATURE, 1).isEnabled)
        assertEquals("当前：开启", value(dialog, HongqiVehicleField.CLIMATE_COMPRESSOR))
        assertEquals("当前：关闭", value(dialog, HongqiVehicleField.CLIMATE_AUTO))
        assertTrue(invocations.isEmpty())
    }

    @Test fun temperatureOutsideTheCommandRangeCanOnlyMoveTowardAReviewedSetpoint() {
        update(HongqiVehicleField.DRIVER_TEMPERATURE, 15.0)
        update(HongqiVehicleField.PASSENGER_TEMPERATURE, 35.0)
        val dialog = show()
        assertFalse(button(dialog, HongqiVehicleField.DRIVER_TEMPERATURE, 0).isEnabled)
        assertTrue(button(dialog, HongqiVehicleField.DRIVER_TEMPERATURE, 1).isEnabled)
        assertTrue(button(dialog, HongqiVehicleField.PASSENGER_TEMPERATURE, 0).isEnabled)
        assertFalse(button(dialog, HongqiVehicleField.PASSENGER_TEMPERATURE, 1).isEnabled)
        button(dialog, HongqiVehicleField.DRIVER_TEMPERATURE, 1).performClick()
        assertEquals(HongqiVehicleControlCommand.Temperature(HongqiVehicleSide.DRIVER, 17.5f), invocations.single().command)
        answer(0, HongqiVehicleControlStatus.REJECTED)
        button(dialog, HongqiVehicleField.PASSENGER_TEMPERATURE, 0).performClick()
        assertEquals(HongqiVehicleControlCommand.Temperature(HongqiVehicleSide.PASSENGER, 32.5f), invocations.last().command)
        assertEquals("当前：15.0 °C", value(dialog, HongqiVehicleField.DRIVER_TEMPERATURE))
        assertEquals("当前：35.0 °C", value(dialog, HongqiVehicleField.PASSENGER_TEMPERATURE))
    }

    private fun show() = session().show().also { shown = it }

    private fun session() = HongqiVehicleControlDialog(activity,
        acquire = { acquired++; Closeable { released++ } },
        snapshot = { samples++; current },
        submit = { command, callback ->
            invocations += Invocation(command, callback)
            Closeable { requestsClosed++ }
        },
    )

    private fun answer(index: Int, status: HongqiVehicleControlStatus) {
        val invocation = invocations[index]
        invocation.callback(HongqiVehicleControlResult(invocation.command, status))
    }

    private fun available(): HongqiVehicleSnapshot {
        val now = SystemClock.elapsedRealtime()
        val readings = linkedMapOf(
            HongqiVehicleField.DRIVER_TEMPERATURE to 25.0,
            HongqiVehicleField.PASSENGER_TEMPERATURE to 26.0,
            HongqiVehicleField.FAN to 2.0,
            HongqiVehicleField.CLIMATE_POWER to 1.0,
            HongqiVehicleField.CLIMATE_AUTO to 0.0,
            HongqiVehicleField.CLIMATE_SYNC to 0.0,
            HongqiVehicleField.CLIMATE_COMPRESSOR to 1.0,
            HongqiVehicleField.CLIMATE_CIRCULATION to 1.0,
            HongqiVehicleField.CLIMATE_FRONT_DEFROST to 0.0,
            HongqiVehicleField.DRIVER_HEAT to 0.0,
            HongqiVehicleField.PASSENGER_HEAT to 0.0,
            HongqiVehicleField.DRIVER_VENT to 0.0,
            HongqiVehicleField.PASSENGER_VENT to 0.0,
            HongqiVehicleField.FRONT_HEAT_SUPPORTED to 1.0,
            HongqiVehicleField.FRONT_VENT_SUPPORTED to 1.0,
        ).mapValues { HongqiVehicleReading(it.value, now) }
        return HongqiVehicleSnapshot(HongqiVehicleConnectionState.READY, readings, capturedAtElapsedMillis = now)
    }

    private fun update(field: HongqiVehicleField, value: Double) {
        val now = SystemClock.elapsedRealtime()
        current = current.copy(readings = current.readings + (field to HongqiVehicleReading(value, now)), capturedAtElapsedMillis = now)
    }

    private fun button(dialog: AlertDialog, field: HongqiVehicleField, index: Int): Button =
        dialog.window!!.decorView.findViewWithTag("hongqi_control_${field.name}_$index")

    private fun value(dialog: AlertDialog, field: HongqiVehicleField) =
        dialog.window!!.decorView.findViewWithTag<TextView>("hongqi_control_value_${field.name}").text.toString()

    private fun outcome(dialog: AlertDialog) =
        dialog.window!!.decorView.findViewWithTag<TextView>("hongqi_control_result").text.toString()

    private fun actions(dialog: AlertDialog) = views(dialog.window!!.decorView).filterIsInstance<Button>()
        .filter { (it.tag as? String)?.startsWith("hongqi_control_") == true }.toList()

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
