package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class NavigationMixingOwnershipTest {
    private var setting: Boolean? = true
    private var pending = false
    private val applied = mutableListOf<Boolean>()
    private val lease = NavigationMixingOwnership({ setting }, { value ->
        assertTrue("Restoration must be journaled before changing DSP", pending)
        applied.add(value); setting = value
    }, { pending }, { pending = it })

    @Test fun idleConstructionDoesNotModifyTheOemSetting() {
        lease.reconcile(false)
        assertTrue(applied.isEmpty()); assertFalse(pending)
    }
    @Test fun activeSessionOverridesOnceAndLastOwnerExitRestores() {
        lease.reconcile(true); lease.reconcile(true)
        assertEquals(listOf(false), applied)
        lease.reconcile(false)
        assertEquals(listOf(false, true), applied); assertFalse(pending)
    }
    @Test fun originalFalseOrUnreadableSettingNeverCreatesRestorationResponsibility() {
        for (value in listOf(false, null)) {
            setting = value; lease.reconcile(true); lease.reconcile(false)
            assertTrue(applied.isEmpty()); assertFalse(pending)
        }
    }
    @Test fun processRestartWithPendingOverrideRestoresBeforeAnySession() {
        pending = true; setting = false
        lease.reconcile(false)
        assertEquals(listOf(true), applied); assertFalse(pending)
    }
    @Test fun externalChangeWinsAndCannotBeOverriddenAgainInTheSameSession() {
        lease.reconcile(true)
        setting = true
        lease.observedExternalChange()
        lease.reconcile(true); lease.reconcile(false)
        assertEquals(listOf(false), applied); assertFalse(pending)
        lease.reconcile(true)
        assertEquals(listOf(false, false), applied)
    }
    @Test fun failedRestorationKeepsTheJournalForNextServiceConnection() {
        lease.reconcile(true)
        lease.withApplier { throw IllegalStateException("service unavailable") }
        runCatching { lease.reconcile(false) }
        assertTrue(pending)
        lease.withApplier { applied.add(it); setting = it }.reconcile(false)
        assertFalse(pending); assertEquals(true, setting)
    }
}
