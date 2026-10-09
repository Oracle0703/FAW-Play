package com.shilapi.xcertplay

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HongqiNavigationGestureTest {
    @Test fun threeFingerLeftOpensOnceAndConsumesTheRest() {
        val gesture = HongqiNavigationControls.Gesture()
        start(gesture, 3)
        assertEquals(1, touch(gesture, MotionEvent.ACTION_MOVE, 3, x = 0f))
        assertEquals(3, touch(gesture, MotionEvent.ACTION_MOVE, 3, x = 250f))
        assertEquals(3, touch(gesture, MotionEvent.ACTION_POINTER_UP, 3))
        assertEquals(3, touch(gesture, MotionEvent.ACTION_UP, 1))
        assertEquals(0, touch(gesture, MotionEvent.ACTION_DOWN, 1))
    }

    @Test fun threeFingerRightClosesInsteadOfToggling() {
        val gesture = HongqiNavigationControls.Gesture()
        start(gesture, 3)
        assertEquals(2, touch(gesture, MotionEvent.ACTION_MOVE, 3, x = 200f))
        start(gesture, 3)
        assertEquals(2, touch(gesture, MotionEvent.ACTION_MOVE, 3, x = 200f))
        start(gesture, 3)
        assertEquals(1, touch(gesture, MotionEvent.ACTION_MOVE, 3, x = 0f))
    }

    @Test fun navigationIsRequiredForBothDirections() {
        for (x in listOf(0f, 200f)) {
            val gesture = HongqiNavigationControls.Gesture()
            start(gesture, 3, navigating = false)
            assertEquals(0, touch(gesture, MotionEvent.ACTION_MOVE, 3, x = x, navigating = false))
        }
    }

    @Test fun oneTwoAndFourFingerSwipesCannotToggleTheInstrument() {
        for (count in listOf(1, 2, 4)) {
            for (x in listOf(0f, 200f)) {
                val gesture = HongqiNavigationControls.Gesture()
                start(gesture, count)
                assertEquals(0, touch(gesture, MotionEvent.ACTION_MOVE, count, x = x))
            }
        }
    }

    @Test fun upwardDownwardAndVerticalDiagonalSwipesDoNotToggleTheInstrument() {
        for ((x, y) in listOf(100f to 0f, 100f to 200f, 0f to 300f)) {
            val gesture = HongqiNavigationControls.Gesture()
            start(gesture, 3)
            assertEquals(0, touch(gesture, MotionEvent.ACTION_MOVE, 3, x, y))
        }
    }

    @Test fun liftingAFingerCancelsTheCurrentGesture() {
        val gesture = HongqiNavigationControls.Gesture()
        start(gesture, 3)
        touch(gesture, MotionEvent.ACTION_POINTER_UP, 3)
        assertEquals(0, touch(gesture, MotionEvent.ACTION_MOVE, 3, x = 0f))
        start(gesture, 3)
        assertEquals(1, touch(gesture, MotionEvent.ACTION_MOVE, 3, x = 0f))
    }

    @Test fun navigationEndingDuringASwipeCancelsTracking() {
        val gesture = HongqiNavigationControls.Gesture()
        start(gesture, 3)
        assertEquals(0, touch(gesture, MotionEvent.ACTION_MOVE, 3, x = 0f, navigating = false))
        assertEquals(0, touch(gesture, MotionEvent.ACTION_MOVE, 3, x = -50f))
    }

    @Test fun addingAFourthFingerRejectsTheWholeSequenceUntilANewTouch() {
        val gesture = HongqiNavigationControls.Gesture()
        start(gesture, 4)
        touch(gesture, MotionEvent.ACTION_POINTER_UP, 4)
        assertEquals(0, touch(gesture, MotionEvent.ACTION_MOVE, 3, x = 0f))
        touch(gesture, MotionEvent.ACTION_POINTER_UP, 3)
        touch(gesture, MotionEvent.ACTION_POINTER_DOWN, 3)
        assertEquals(0, touch(gesture, MotionEvent.ACTION_MOVE, 3, x = -50f))
        start(gesture, 3)
        assertEquals(1, touch(gesture, MotionEvent.ACTION_MOVE, 3, x = 0f))
    }

    @Test fun pinchAndSingleMovingFingerCannotToggleTheInstrument() {
        for (xs in listOf(listOf(-100f, -50f, 300f), listOf(-200f, 150f, 200f))) {
            val gesture = HongqiNavigationControls.Gesture()
            start(gesture, 3)
            assertEquals(0, touch(gesture, MotionEvent.ACTION_MOVE, 3, fingerXs = xs))
        }
    }

    @Test fun shortMovementCannotToggleTheInstrument() {
        val gesture = HongqiNavigationControls.Gesture()
        start(gesture, 3)
        assertEquals(0, touch(gesture, MotionEvent.ACTION_MOVE, 3, x = 40f))
        assertEquals(1, touch(gesture, MotionEvent.ACTION_MOVE, 3, x = 0f))
    }

    @Test fun cancellingASwipeDoesNotToggleOnLaterMovement() {
        val gesture = HongqiNavigationControls.Gesture()
        start(gesture, 3)
        assertEquals(0, touch(gesture, MotionEvent.ACTION_CANCEL, 3))
        assertEquals(0, touch(gesture, MotionEvent.ACTION_MOVE, 3, x = 0f))
    }

    @Test fun acceptedGestureIsDrainedWhenNavigationEndsBeforeFingersLift() {
        val gesture = HongqiNavigationControls.Gesture()
        start(gesture, 3)
        assertEquals(1, touch(gesture, MotionEvent.ACTION_MOVE, 3, x = 0f))
        assertEquals(3, touch(gesture, MotionEvent.ACTION_MOVE, 3, x = -50f, navigating = false))
        assertEquals(3, touch(gesture, MotionEvent.ACTION_UP, 1, navigating = false))
        start(gesture, 3, navigating = false)
        assertEquals(0, touch(gesture, MotionEvent.ACTION_MOVE, 3, x = 0f, navigating = false))
    }

    private fun start(gesture: HongqiNavigationControls.Gesture, count: Int, navigating: Boolean = true) {
        touch(gesture, MotionEvent.ACTION_DOWN, 1, navigating = navigating)
        for (fingers in 2..count) touch(gesture, MotionEvent.ACTION_POINTER_DOWN, fingers, navigating = navigating)
    }

    private fun touch(gesture: HongqiNavigationControls.Gesture, action: Int, count: Int,
        x: Float = 100f, y: Float = 100f, navigating: Boolean = true,
        fingerXs: List<Float>? = null): Int {
        val pointers = Array(count) { index -> MotionEvent.PointerProperties().apply {
            id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val positions = Array(count) { index -> MotionEvent.PointerCoords().apply {
            this.x = fingerXs?.get(index) ?: (x + index * 50f)
            this.y = y; pressure = 1f; size = 1f
        } }
        val indexed = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
            action or ((count - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val event = MotionEvent.obtain(0, 10, indexed, count, pointers, positions, 0, 0, 1f, 1f, 0, 0, 0, 0)
        return try { gesture.accept(event, 1f, navigating) } finally { event.recycle() }
    }
}
