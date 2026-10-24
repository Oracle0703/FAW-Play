package com.shilapi.xcertplay

import com.shilapi.xcertplay.DiLink51ClusterLayout.Theme
import com.shilapi.xcertplay.DiLink51ClusterLayout.Plan
import org.junit.Assert.*
import org.junit.Test

class DiLink51ClusterLayoutTest {
    private val names = listOf(DiLink51ClusterLayout.BASE, DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.SIDE)
    private val firmware = DiLink51ClusterLayout.FINGERPRINT

    @Test fun mapUsesFullLayerWhileBothOtherThemesUseSideLayer() {
        assertEquals(DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.displayName(names, firmware, Theme.MAP))
        for (theme in listOf(Theme.SCENARIO, Theme.SIMPLE)) {
            assertEquals(DiLink51ClusterLayout.SIDE, DiLink51ClusterLayout.displayName(names, firmware, theme))
            assertEquals(Plan(1320, 0, 600, 720, false), DiLink51ClusterLayout.plan(1920, 720, theme))
        }
    }

    @Test fun missingSideLayerCannotCoverScenarioWithAFullMap() {
        assertNull(DiLink51ClusterLayout.displayName(listOf(DiLink51ClusterLayout.BASE, DiLink51ClusterLayout.FULL), firmware, Theme.SCENARIO))
    }

    @Test fun fullMapKeepsSpeedAndGearBandsOutsideTheVideoViewport() {
        val plan = DiLink51ClusterLayout.plan(1920, 720, Theme.MAP)!!
        assertTrue(plan.top >= 144)
        assertTrue(plan.top + plan.height <= 624)
        assertEquals(0, plan.left)
        assertEquals(1920, plan.width)
        assertNull(DiLink51ClusterLayout.streamConfig(Theme.MAP).safeArea)
    }

    @Test fun eachThemeRequestsItsVisibleViewportWithoutCroppingAFullPanelStream() {
        for (theme in Theme.entries) {
            val plan = DiLink51ClusterLayout.plan(1920, 720, theme)!!
            val config = DiLink51ClusterLayout.streamConfig(theme,
                com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content.INSTRUMENTS)
            assertEquals(plan.width, config.widthPixels)
            assertEquals(plan.height, config.heightPixels)
            assertNull(config.safeArea)
            assertEquals(com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content.INSTRUMENTS.url, config.initialUrl)
            assertEquals(0, config.features)
        }
        assertEquals(600, DiLink51ClusterLayout.streamConfig(Theme.SIMPLE).widthPixels)
        assertEquals(480, DiLink51ClusterLayout.streamConfig(Theme.MAP).heightPixels)
    }

    @Test fun diLink5AndUnknownFirmwareRetainThePrDisplaySelection() {
        for (fingerprint in listOf("BYD-AUTO/DiLink5.0/DiLink5.0:12/test", "unknown")) {
            assertFalse(DiLink51ClusterLayout.supported(fingerprint))
            assertEquals(DiLink51ClusterLayout.BASE, DiLink51ClusterLayout.displayName(names, fingerprint, Theme.SIMPLE))
            assertEquals(DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.displayName(names.drop(1), fingerprint, Theme.MAP))
        }
    }

    @Test fun unmeasuredPanelSizesAreNotGivenTheWrongReadoutMask() {
        assertNull(DiLink51ClusterLayout.plan(1280, 480, Theme.MAP))
    }
}
