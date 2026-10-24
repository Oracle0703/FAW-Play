package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayViewAreasTest {
    @Test fun declaresAllSavedSizesInTheCurrentOrientation() {
        val saved = (1..10).map { (800 + it * 20) to 600 }
        val areas = CarPlayViewAreas.build(1280, 720, saved, 10) { _, _ -> null }
        assertEquals(11, areas.size)
        assertEquals(0, areas.indexFor(1280, 720))
        assertNull(areas.indexFor(720, 1280))
        saved.forEach { (w, h) ->
            assertNotNull(areas.indexFor(w, h))
            assertNull(areas.indexFor(h, w))
        }
        assertFalse(areas.turnsWithScreen)
        assertNull(areas.indexFor(777, 555, true, false))
        assertNotNull(areas.splitScreenRatio)
    }

    @Test fun mixedOrientationsDoNotDeclareALongestEdgeSquare() {
        val areas = CarPlayViewAreas.build(1920, 1080, listOf(1080 to 1920, 1920 to 720), 10) { _, _ -> null }
        assertEquals(1920, areas.widthPixels)
        assertEquals(1080, areas.heightPixels)
        assertEquals(1920, areas.activityWidthPixels)
        assertEquals(1080, areas.activityHeightPixels)
        // A saved portrait size is offered through its landscape swap.
        assertEquals(0, areas.indexFor(1920, 1080))
        assertNotNull(areas.indexFor(1920, 720))
        assertEquals(2, areas.size)

        val portrait = CarPlayViewAreas.build(1080, 1920, listOf(1920 to 1080), 10) { _, _ -> null }
        assertEquals(1, portrait.size)
        assertEquals(1080, portrait.widthPixels)
        assertEquals(1920, portrait.heightPixels)
    }

    @Test fun deduplicatesSizesAndScalesSafeAreaForEachIndex() {
        val areas = CarPlayViewAreas.build(1280, 720, listOf(1280 to 720, 1000 to 720), 5) { w, h ->
            com.shilapi.xcertplay.airplay.SafeAreaRect(20, 20, w - 20, h - 20)
        }
        assertEquals(2, areas.size)
        assertEquals(640, areas.areas[0].widthPixels)
        assertEquals(360, areas.areas[0].heightPixels)
        assertEquals(10, areas.areas[0].safeArea?.left)
        assertEquals(10, areas.areas[1].safeArea?.top)
    }
}
