package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class AutunnFactoryTurnCodeTest {
    @Test fun factoryUsesDifferentCodesFromBroadcasts() {
        assertEquals(1, AutunnFactoryTurnCode.map(2, 0))
        assertEquals(2, AutunnFactoryTurnCode.map(3, 0))
        assertEquals(5, AutunnFactoryTurnCode.map(5, 0))
        assertEquals(11, AutunnFactoryTurnCode.map(9, 0))
        assertEquals(48, AutunnFactoryTurnCode.map(15, 0))
    }
    @Test fun roundaboutDirectionAndBounds() {
        assertEquals(25, AutunnFactoryTurnCode.map(11, 1))
        assertEquals(34, AutunnFactoryTurnCode.map(12, 10))
        assertEquals(35, AutunnFactoryTurnCode.map(17, 1))
        assertEquals(44, AutunnFactoryTurnCode.map(18, 10))
        assertEquals(13, AutunnFactoryTurnCode.map(11, 11))
        assertEquals(14, AutunnFactoryTurnCode.map(17, 0))
    }
    @Test fun invalidInstructionsAreNotMadeIntoStraightArrows() {
        assertNull(AutunnFactoryTurnCode.map(-1, 0))
        assertNull(AutunnFactoryTurnCode.map(0, 0))
        assertNull(AutunnFactoryTurnCode.map(29, 0))
    }
}
