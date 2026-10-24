package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import com.shilapi.xcertplay.hud.ClusterLane
import com.shilapi.xcertplay.hud.ClusterLaneGuidance
import com.shilapi.xcertplay.hud.ClusterLaneStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCanvas

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "w1920dp-h720dp-land-mdpi")
class ClusterLaneGuidanceViewTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun noDirectionsAreInventedBeforeGuidanceOrAfterWithdrawal() {
        val view = ClusterLaneGuidanceView(context)
        assertEquals(View.GONE, view.visibility)
        assertEquals(0, frame(view).pathPaintHistoryCount)
        view.setGuidance(ClusterLaneGuidance(7, listOf(lane(0, listOf(0)))))
        assertEquals(View.VISIBLE, view.visibility)
        assertEquals(1, frame(view).pathPaintHistoryCount)
        view.setGuidance(null)
        assertEquals(View.GONE, view.visibility)
        assertEquals(0, frame(view).pathPaintHistoryCount)
        view.setGuidance(ClusterLaneGuidance(7, emptyList()))
        assertEquals(View.GONE, view.visibility)
    }

    @Test fun laneSourceOrderAndStatusColorsRemainIntactForNonmonotonicIndices() {
        val view = ClusterLaneGuidanceView(context)
        view.setGuidance(ClusterLaneGuidance(3, listOf(
            lane(2, listOf(90), ClusterLaneStatus.PREFERRED),
            lane(0, listOf(-90), ClusterLaneStatus.NOT_GOOD),
            lane(1, listOf(0), ClusterLaneStatus.GOOD),
        )))
        val drawn = frame(view)
        assertEquals(3, drawn.pathPaintHistoryCount)
        assertEquals(ClusterLaneGuidanceView.PREFERRED_COLOR, drawn.getDrawnPathPaint(0).color)
        assertEquals(ClusterLaneGuidanceView.NOT_GOOD_COLOR, drawn.getDrawnPathPaint(1).color)
        assertEquals(ClusterLaneGuidanceView.GOOD_COLOR, drawn.getDrawnPathPaint(2).color)
        val left = bounds(drawn, 0)
        val straight = bounds(drawn, 1)
        val right = bounds(drawn, 2)
        assertTrue(left.right < straight.left)
        assertTrue(straight.right < right.left)
    }

    @Test fun multiDirectionLaneDrawsEachRealBranchAndItsSeparatePreferredAngle() {
        val view = ClusterLaneGuidanceView(context)
        view.setGuidance(ClusterLaneGuidance(1, listOf(
            ClusterLane(0, ClusterLaneStatus.GOOD, listOf(-90, 0), 90),
        )))
        val drawn = frame(view)
        assertEquals(3, drawn.pathPaintHistoryCount)
        assertEquals(ClusterLaneGuidanceView.GOOD_COLOR, drawn.getDrawnPathPaint(0).color)
        assertEquals(ClusterLaneGuidanceView.GOOD_COLOR, drawn.getDrawnPathPaint(1).color)
        assertEquals(ClusterLaneGuidanceView.PREFERRED_COLOR, drawn.getDrawnPathPaint(2).color)
        val left = bounds(drawn, 0)
        val straight = bounds(drawn, 1)
        val highlightedRight = bounds(drawn, 2)
        assertTrue(left.left < straight.left - 10f)
        assertTrue(highlightedRight.right > straight.right + 10f)
        assertEquals(straight.centerX(), (left.right + highlightedRight.left) / 2f, 0.1f)
    }

    @Test fun signedUTurnsRemainOnTheirCorrectSideWithDownwardArrowheads() {
        val view = ClusterLaneGuidanceView(context)
        view.setGuidance(ClusterLaneGuidance(1, listOf(lane(0, listOf(-180, 180)))))
        val drawn = frame(view)
        assertEquals(2, drawn.pathPaintHistoryCount)
        val left = bounds(drawn, 0)
        val right = bounds(drawn, 1)
        assertTrue(left.left < right.left - 10f)
        assertTrue(right.right > left.right + 10f)
        assertEquals(left.bottom, right.bottom, 0.1f)
    }

    @Test fun aRowNeverDropsSomeOfTheRealLanesToFit() {
        val view = ClusterLaneGuidanceView(context)
        view.setGuidance(ClusterLaneGuidance(1, (0 until 12).map { lane(it, listOf(0)) }))
        assertEquals(View.VISIBLE, view.visibility)
        val drawn = frame(view)
        assertEquals(12, drawn.pathPaintHistoryCount)
        val row = drawn.getDrawnRoundRect(0).rect
        for (index in 0 until 12) assertTrue(row.contains(bounds(drawn, index)))
        view.setGuidance(ClusterLaneGuidance(1, (0 until 13).map { lane(it, listOf(0)) }))
        assertEquals(View.GONE, view.visibility)
        assertEquals(0, frame(view).pathPaintHistoryCount)
    }

    @Test fun defaultAndScaledRowLeaveRightJunctionAndBottomEtaRegionsUntouched() {
        val native = ClusterLaneGuidanceView.stripBounds(1920, 720, 10)
        assertTrue(native.right <= 540f)
        assertTrue(native.bottom < 622f)
        assertTrue(native.top >= 500f)
        val half = ClusterLaneGuidanceView.stripBounds(960, 360, 10)
        assertEquals(native.left / 2f, half.left, 0f)
        assertEquals(native.top / 2f, half.top, 0f)
        assertEquals(native.right / 2f, half.right, 0f)
        assertEquals(native.bottom / 2f, half.bottom, 0f)
    }

    @Test fun noArrowIsMadeUpForALaneWithNoDirectionData() {
        val view = ClusterLaneGuidanceView(context)
        view.setGuidance(ClusterLaneGuidance(1, listOf(lane(0, emptyList()))))
        assertFalse(frame(view).hasDrawnPath())
    }

    @Test fun identicalStateDoesNotScheduleContinuousRedraws() {
        val view = ClusterLaneGuidanceView(context)
        val update = ClusterLaneGuidance(1, listOf(lane(0, listOf(-90, 0))))
        view.setGuidance(update)
        val shadow = shadowOf(view)
        shadow.clearWasInvalidated()
        view.setGuidance(update.copy(lanes = update.lanes.toList()))
        assertFalse(shadow.wasInvalidated())
        view.setGuidance(update.copy(index = 2))
        assertTrue(shadow.wasInvalidated())
    }

    private fun lane(index: Int, angles: List<Int>, status: ClusterLaneStatus = ClusterLaneStatus.GOOD) =
        ClusterLane(index, status, angles, null)

    private fun bounds(frame: ShadowCanvas, index: Int): RectF = RectF().also {
        frame.getDrawnPath(index).computeBounds(it, true)
    }

    private fun frame(view: ClusterLaneGuidanceView): ShadowCanvas {
        view.layout(0, 0, 1920, 720)
        val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        return try { shadowOf(Canvas(bitmap).also(view::draw)) } finally { bitmap.recycle() }
    }
}
