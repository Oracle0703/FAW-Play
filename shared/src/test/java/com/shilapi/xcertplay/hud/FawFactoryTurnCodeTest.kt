package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class FawFactoryTurnCodeTest {
    @Test fun factoryUsesDifferentCodesFromBroadcasts() {
        assertEquals(1, FawFactoryTurnCode.map(2, 0))
        assertEquals(2, FawFactoryTurnCode.map(3, 0))
        assertEquals(5, FawFactoryTurnCode.map(5, 0))
        assertEquals(11, FawFactoryTurnCode.map(9, 0))
        assertEquals(48, FawFactoryTurnCode.map(15, 0))
    }
    @Test fun roundaboutDirectionAndBounds() {
        assertEquals(25, FawFactoryTurnCode.map(11, 1))
        assertEquals(34, FawFactoryTurnCode.map(12, 10))
        assertEquals(35, FawFactoryTurnCode.map(17, 1))
        assertEquals(44, FawFactoryTurnCode.map(18, 10))
        assertEquals(13, FawFactoryTurnCode.map(11, 11))
        assertEquals(14, FawFactoryTurnCode.map(17, 0))
    }
    @Test fun invalidInstructionsAreNotMadeIntoStraightArrows() {
        assertNull(FawFactoryTurnCode.map(-1, 0))
        assertNull(FawFactoryTurnCode.map(0, 0))
        assertNull(FawFactoryTurnCode.map(29, 0))
    }
}
