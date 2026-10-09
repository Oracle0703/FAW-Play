package com.shilapi.xcertplay.review

import android.view.InputDevice
import android.view.MotionEvent
import com.shilapi.xcertplay.airplay.AirPlayHid
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayTouchContinuityTest {
    private val mapper = CarPlayTouchMapper()
    @Test fun androidKeepsPhysicalPointerIdWhenFirstPointerIsLifted() {
        val pointerUp = event(
            MotionEvent.ACTION_POINTER_UP,
            ids = intArrayOf(10, 20),
            xs = floatArrayOf(200f, 1400f),
        )
        val remainingMove = event(
            MotionEvent.ACTION_MOVE,
            ids = intArrayOf(20),
            xs = floatArrayOf(1450f),
        )
        try {
            assertEquals(MotionEvent.ACTION_POINTER_UP, pointerUp.actionMasked)
            assertEquals(0, pointerUp.actionIndex)
            assertEquals(20, pointerUp.getPointerId(1))
            assertEquals(20, remainingMove.getPointerId(0))
            assertEquals(-1, remainingMove.findPointerIndex(10))
            assertEquals(0, remainingMove.findPointerIndex(20))
        } finally {
            pointerUp.recycle()
            remainingMove.recycle()
        }
    }

    @Test fun remainingPhysicalPointerKeepsItsHidSlotAcrossFirstPointerUp() {
        val events = listOf(
            event(MotionEvent.ACTION_DOWN, intArrayOf(10), floatArrayOf(200f)),
            event(
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                intArrayOf(10, 20),
                floatArrayOf(200f, 1400f),
            ),
            event(MotionEvent.ACTION_POINTER_UP, intArrayOf(10, 20), floatArrayOf(200f, 1400f)),
            event(MotionEvent.ACTION_MOVE, intArrayOf(20), floatArrayOf(1450f)),
        )
        try {
            assertEquals(20, events[1].getPointerId(1))
            assertEquals(20, events[2].getPointerId(1))
            assertEquals(20, events[3].getPointerId(0))
            val reports = events.map { motionEvent ->
                val contacts = mapper.contacts(motionEvent, WIDTH, HEIGHT)
                // Same conversion from normalized coordinates to HID display units as sendTouch.
                AirPlayHid.touchReport(contacts.map { it.copy(x = it.x * WIDTH, y = it.y * HEIGHT) })
            }
            reports.forEachIndexed { index, report ->
                println("review-event=$index pointerIds=${(0 until events[index].pointerCount).map(events[index]::getPointerId)} " +
                    "hidDown=${listOf(down(report, 0), down(report, 1))} " +
                    "hidX=${listOf(x(report, 0), x(report, 1))}")
            }
            assertTrue(down(reports[0], 0))
            assertFalse(down(reports[0], 1))
            assertTrue(down(reports[1], 0))
            assertTrue(down(reports[1], 1))
            assertFalse(down(reports[2], 0))
            assertTrue(down(reports[2], 1))
            assertEquals(1400, x(reports[2], 1))

            assertTrue(
                "Physical Android pointer 20 must remain down in HID slot 1 after pointer 10 is lifted",
                down(reports[3], 1),
            )
            assertFalse("Vacated HID slot 0 must remain released", down(reports[3], 0))
            assertEquals(1450, x(reports[3], 1))
        } finally {
            events.forEach(MotionEvent::recycle)
        }
    }

    private fun event(action: Int, ids: IntArray, xs: FloatArray): MotionEvent {
        val properties = Array(ids.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = ids[index]
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(ids.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = xs[index]
                y = 300f
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            0L, 16L, action, ids.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    private fun down(report: ByteArray, slot: Int): Boolean = (report[slot * 6 + 1].toInt() and 1) != 0
    private fun x(report: ByteArray, slot: Int): Int =
        (report[slot * 6 + 2].toInt() and 0xff) or ((report[slot * 6 + 3].toInt() and 0xff) shl 8)

    companion object {
        private const val WIDTH = 1920
        private const val HEIGHT = 720
    }
}
