package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import android.widget.ScrollView
import android.view.View
import android.view.ViewGroup
import com.shilapi.xcertplay.hud.HongqiVehicleConnectionState
import com.shilapi.xcertplay.hud.HongqiVehicleField
import com.shilapi.xcertplay.hud.HongqiVehicleReading
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
class HongqiVehicleDataDialogTest {
    private lateinit var host: ActivityController<Activity>
    private lateinit var activity: Activity
    private var acquired = 0
    private var released = 0
    private var samples = 0
    private lateinit var current: HongqiVehicleSnapshot
    private var shown: AlertDialog? = null

    @Before fun setUp() {
        host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        activity = host.get()
        current = snapshot()
    }

    @After fun tearDown() {
        shown?.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        if (!activity.isDestroyed) host.pause().stop().destroy()
    }

    @Test fun explicitDialogOwnsOneLeaseAndDismissStopsBothPollingAndOwnership() {
        val session = session()
        val dialog = session.show().also { shown = it }
        session.show()
        assertTrue(dialog.isShowing)
        assertEquals(1, acquired)
        assertEquals(1, samples)

        current = snapshot(mapOf(HongqiVehicleField.SPEED to reading(45.0)))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(2, samples)
        assertTrue(value(dialog, HongqiVehicleField.SPEED).startsWith("原始值 45 ·"))

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertEquals(1, released)
        val finalSamples = samples
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(finalSamples, samples)
        assertEquals(1, released)
    }

    @Test fun stoppingTheHostDismissesAndReleasesBeforeLaterDestruction() {
        val dialog = session().show().also { shown = it }
        host.pause().stop()
        assertFalse(dialog.isShowing)
        assertEquals(1, released)
        val finalSamples = samples
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(finalSamples, samples)
        host.destroy()
        assertEquals(1, released)
    }

    @Test fun destructionAlsoReleasesWithoutDependingOnAnEarlierStopCallback() {
        val dialog = session().show().also { shown = it }
        host.pause().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(dialog.isShowing)
        assertEquals(1, released)
        assertEquals(1, samples)
    }

    @Test fun failedAndNonFiniteReadingsNeverLookLikeValidVehicleValues() {
        current = snapshot(mapOf(
            HongqiVehicleField.GEAR to reading(64.0),
            HongqiVehicleField.SPEED to reading(91.0, "stale"),
            HongqiVehicleField.AMBIENT_TEMPERATURE to reading(Double.NaN),
        ))
        val dialog = session().show().also { shown = it }
        assertTrue(value(dialog, HongqiVehicleField.GEAR).startsWith("原始值 64 ·"))
        assertEquals("未知", value(dialog, HongqiVehicleField.SPEED))
        assertEquals("未知", value(dialog, HongqiVehicleField.AMBIENT_TEMPERATURE))
        assertEquals("未知", value(dialog, HongqiVehicleField.DRIVER_DOOR))
    }

    @Test fun everyReviewedFieldHasAScrollableReadOnlyRowAndNewNumericValuesStayRaw() {
        current = snapshot(mapOf(
            HongqiVehicleField.TOTAL_MILEAGE to reading(25_509.0),
            HongqiVehicleField.REAR_LEFT_TIRE_PRESSURE to reading(255.0),
            HongqiVehicleField.CLIMATE_COMPRESSOR to reading(2.0),
        ))
        val dialog = session().show().also { shown = it }
        for (field in HongqiVehicleField.entries) assertNotNull(value(dialog, field))
        assertTrue(value(dialog, HongqiVehicleField.TOTAL_MILEAGE).startsWith("原始值 25509 ·"))
        assertTrue(value(dialog, HongqiVehicleField.REAR_LEFT_TIRE_PRESSURE).startsWith("原始值 255 ·"))
        assertTrue(value(dialog, HongqiVehicleField.CLIMATE_COMPRESSOR).startsWith("原始值 2 ·"))
        assertTrue(views(dialog.window!!.decorView).any { it is ScrollView })
    }

    @Test fun staleFutureAndDisconnectedReadingsCannotAppearCurrent() {
        val now = SystemClock.elapsedRealtime()
        current = snapshot(mapOf(
            HongqiVehicleField.SPEED to HongqiVehicleReading(45.0, now - 6_001),
            HongqiVehicleField.TOTAL_MILEAGE to HongqiVehicleReading(25_509.0, now + 60_000),
            HongqiVehicleField.FUEL_REMAINING to HongqiVehicleReading(46.0, now),
        ))
        val dialog = session().show().also { shown = it }
        assertEquals("未知", value(dialog, HongqiVehicleField.SPEED))
        assertEquals("未知", value(dialog, HongqiVehicleField.TOTAL_MILEAGE))
        assertTrue(value(dialog, HongqiVehicleField.FUEL_REMAINING).startsWith("原始值 46 ·"))
        current = current.copy(status = HongqiVehicleConnectionState.UNAVAILABLE)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals("未知", value(dialog, HongqiVehicleField.FUEL_REMAINING))
    }

    private fun session() = HongqiVehicleDataDialog(activity,
        acquire = { acquired++; Closeable { released++ } },
        snapshot = { samples++; current },
    )

    private fun snapshot(readings: Map<HongqiVehicleField, HongqiVehicleReading> = emptyMap()) =
        HongqiVehicleSnapshot(HongqiVehicleConnectionState.READY, readings)

    private fun reading(raw: Double?, failure: String? = null) = HongqiVehicleReading(raw, 0L, failure)

    private fun value(dialog: AlertDialog, field: HongqiVehicleField) =
        dialog.window!!.decorView.findViewWithTag<TextView>("hongqi_vehicle_${field.name}").text.toString()

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
