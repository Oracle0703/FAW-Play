package com.shilapi.xcertplay.airplay.rcs.uisync

import org.junit.Assert.*
import org.junit.Test

class HongqiHs5UiSyncTargetTest {
    @Test
    fun layoutIsAppliedToHostAndUnknownComponentsNeverBecomeActive() {
        val applied = mutableListOf<HongqiHs5UiState>()
        val target = HongqiHs5UiSyncTarget(onApply = { applied += it; true })
        assertTrue(target.apply(command(CarPlayUiSyncCommand.REQUEST_LAYOUT,
            "layout" to HongqiHs5UiLayouts.OVERVIEW,
            "activeComponents" to listOf("speed", "gear"), "fadeOutOldLayout" to true)))
        assertEquals(setOf("speed", "gear"), target.snapshot().activeComponents)
        assertTrue(target.snapshot().fadeOutOldLayout)
        assertFalse(target.apply(command(CarPlayUiSyncCommand.REQUEST_LAYOUT,
            "layout" to HongqiHs5UiLayouts.OVERVIEW,
            "activeComponents" to listOf("unsupported-warning"))))
        assertEquals(1, applied.size)
        assertEquals(setOf("speed", "gear"), target.snapshot().activeComponents)
    }

    @Test
    fun rejectedHostApplicationDoesNotAdvanceState() {
        val target = HongqiHs5UiSyncTarget(onApply = { false })
        assertFalse(target.apply(command(CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE,
            "appearanceMode" to "dark")))
        assertNull(target.snapshot().appearanceMode)
    }

    @Test
    fun requestWithoutLayoutNeedsAnExistingAppliedLayout() {
        val target = target()
        assertFalse(target.apply(command(CarPlayUiSyncCommand.REQUEST_LAYOUT)))
        assertTrue(target.apply(command(CarPlayUiSyncCommand.REQUEST_LAYOUT,
            "layout" to HongqiHs5UiLayouts.NAVIGATION)))
        assertTrue(target.apply(command(CarPlayUiSyncCommand.REQUEST_LAYOUT)))
        assertEquals(setOf("navigation"), target.snapshot().activeComponents)
    }

    @Test
    fun floatFocusAndUnrecognizedFieldsAreRejectedWithoutHostSideEffect() {
        var applies = 0
        val target = HongqiHs5UiSyncTarget(onApply = { applies++; true })
        assertFalse(target.apply(command(CarPlayUiSyncCommand.REQUEST_FOCUS, "focusToken" to 1.5)))
        assertFalse(target.apply(command(CarPlayUiSyncCommand.REQUEST_FOCUS,
            "focusToken" to 1L, "guessedMode" to true)))
        assertEquals(0, applies)
        assertTrue(target.apply(command(CarPlayUiSyncCommand.GIVE_FOCUS, "focusToken" to 17L)))
        assertEquals(17L, target.snapshot().focusToken)
    }

    @Test
    fun appearanceAndLayoutBracketsAreApplied() {
        val target = target()
        assertTrue(target.apply(command(CarPlayUiSyncCommand.APPEARANCE_CHANGE_START)))
        assertTrue(target.apply(command(CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE,
            "appearanceMode" to "dark")))
        assertTrue(target.apply(command(CarPlayUiSyncCommand.APPEARANCE_PREFERENCE_CHANGE,
            "preference" to "automatic", "override" to false)))
        assertTrue(target.apply(command(CarPlayUiSyncCommand.APPEARANCE_CHANGE_END)))
        assertFalse(target.snapshot().appearanceChanging)
        assertEquals("dark", target.snapshot().appearanceMode)
        assertFalse(target.apply(command(CarPlayUiSyncCommand.END_LAYOUT_CHANGE)))
        assertTrue(target.apply(command(CarPlayUiSyncCommand.REQUEST_LAYOUT,
            "layout" to HongqiHs5UiLayouts.NAVIGATION)))
        assertTrue(target.snapshot().layoutChanging)
        assertTrue(target.apply(command(CarPlayUiSyncCommand.END_LAYOUT_CHANGE)))
        assertFalse(target.snapshot().layoutChanging)
    }

    @Test
    fun driveModeAssignmentSelectsOnlyDeclaredLocalLayout() {
        val target = target()
        assertFalse(target.apply(command(CarPlayUiSyncCommand.DRIVE_MODE_DYNAMIC_ASSIGNMENT_CHANGE,
            "driveModeToLayoutIdAssignments" to mapOf("sport" to "unknown"))))
        assertTrue(target.apply(command(CarPlayUiSyncCommand.DRIVE_MODE_DYNAMIC_ASSIGNMENT_CHANGE,
            "driveModeToLayoutIdAssignments" to mapOf("sport" to HongqiHs5UiLayouts.OVERVIEW))))
        assertTrue(target.apply(command(CarPlayUiSyncCommand.DRIVE_MODE_CHANGE, "driveMode" to "sport")))
        assertEquals(HongqiHs5UiLayouts.OVERVIEW, target.snapshot().layoutId)
        assertEquals(HongqiHs5UiLayouts.ALL[1].components, target.snapshot().activeComponents)
    }

    @Test
    fun opaqueUiConfigurationIsCopiedAndNotInterpretedAsCode() {
        val bytes = byteArrayOf(1, 2, 3)
        val target = target()
        assertTrue(target.apply(command(CarPlayUiSyncCommand.UI_CONFIGURATION_CHANGE,
            "uiConfiguration" to "extendedMode", "data" to bytes)))
        bytes[0] = 99
        assertArrayEquals(byteArrayOf(1, 2, 3), target.snapshot().uiConfigurationData as ByteArray)
    }

    @Test
    fun catalogOnlyCommandsHaveNoSyntheticSuccess() {
        val target = target()
        listOf(CarPlayUiSyncCommand.LAYOUT_CHANGE, CarPlayUiSyncCommand.TRANSITION_DATA,
            CarPlayUiSyncCommand.METADATA_TRANSFER, CarPlayUiSyncCommand.PALETTE_CHANGE,
            CarPlayUiSyncCommand.DRIVE_MODE_THEME_CONFIGURATION_OVERRIDE_CHANGE).forEach {
            assertFalse(target.apply(command(it)))
        }
    }

    @Test
    fun inactiveReleasesPresentationAndDropsRemoteState() {
        var inactive = 0
        val target = HongqiHs5UiSyncTarget(onApply = { true }, onInactive = { inactive++ })
        target.apply(command(CarPlayUiSyncCommand.REQUEST_LAYOUT,
            "layout" to HongqiHs5UiLayouts.OVERVIEW))
        target.inactive()
        assertEquals(HongqiHs5UiState(), target.snapshot())
        assertEquals(1, inactive)
    }

    private fun target() = HongqiHs5UiSyncTarget(onApply = { true })
    private fun command(command: CarPlayUiSyncCommand, vararg fields: Pair<String, Any?>) =
        CarPlayUiSyncCommandPayload(command, linkedMapOf(*fields))
}
