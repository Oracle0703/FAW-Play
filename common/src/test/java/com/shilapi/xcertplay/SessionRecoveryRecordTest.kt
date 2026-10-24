package com.shilapi.xcertplay

import android.app.Application
import android.content.Context
import android.provider.Settings
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SessionRecoveryRecordTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("faw_session_control", Context.MODE_PRIVATE)

    @Before fun setUp() {
        prefs.edit().clear().commit()
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 7)
    }

    @After fun tearDown() { prefs.edit().clear().commit() }

    @Test fun parkingGenerationAndConsumedRetryBudgetSurviveProcessRecoveryInTheSameBoot() {
        assertNull(SessionRecoveryRecord.load(app))
        val parked = SessionRecoveryRecord(15L, false, false, true, false, 3, true,
            useSawIgnitionOn = true, explicitUse = true)
        SessionRecoveryRecord.save(app, parked)
        assertEquals(parked, SessionRecoveryRecord.load(app))
    }

    @Test fun manualAndTerminalStopsAreRestoredWithoutGrantingAnotherRetryBudget() {
        val manual = SessionRecoveryRecord(8L, false, true, false, false, 2, false)
        SessionRecoveryRecord.save(app, manual)
        assertEquals(manual, SessionRecoveryRecord.load(app))
        val terminal = manual.copy(terminated = true, retries = 4, retryStopped = true)
        SessionRecoveryRecord.save(app, terminal)
        assertEquals(terminal, SessionRecoveryRecord.load(app))
    }

    @Test fun olderRecordsDoNotInventAnExplicitConnectionRequest() {
        prefs.edit().putInt("boot", 7).putLong("use", 6L).putBoolean("desired", true)
            .putBoolean("use_ignited", true).commit()
        assertEquals(SessionRecoveryRecord(6L, true, false, false, false, 0, false,
            useSawIgnitionOn = true, explicitUse = false), SessionRecoveryRecord.load(app))
    }

    @Test fun aUserActionRequirementSurvivesRecoveryAndCanBeExplicitlyCleared() {
        val blocked = SessionRecoveryRecord(18L, true, false, false, false, 1, true,
            explicitUse = true, retryRequiresUserAction = true)
        SessionRecoveryRecord.save(app, blocked)
        assertEquals(blocked, SessionRecoveryRecord.load(app))

        val cleared = blocked.copy(retryRequiresUserAction = false)
        SessionRecoveryRecord.save(app, cleared)
        assertEquals(cleared, SessionRecoveryRecord.load(app))
    }

    @Test fun aLegacyStopBeforeTheRetryLimitConservativelyKeepsTheManualRepairRequirement() {
        val retries = WirelessStartupPolicy.MAX_STARTUP_RETRIES - 1
        prefs.edit().putInt("boot", 7).putLong("use", 19L).putBoolean("desired", true)
            .putInt("retries", retries).putBoolean("retry_stopped", true).commit()

        assertEquals(SessionRecoveryRecord(19L, true, false, false, false, retries, true,
            retryRequiresUserAction = true), SessionRecoveryRecord.load(app))
    }

    @Test fun aLegacyExhaustedBudgetDoesNotInventAManualRepairRequirement() {
        val retries = WirelessStartupPolicy.MAX_STARTUP_RETRIES
        prefs.edit().putInt("boot", 7).putLong("use", 20L).putBoolean("desired", true)
            .putInt("retries", retries).putBoolean("retry_stopped", true).commit()

        assertEquals(SessionRecoveryRecord(20L, true, false, false, false, retries, true,
            retryRequiresUserAction = false), SessionRecoveryRecord.load(app))
    }

    @Test fun anExplicitFalseRequirementOverridesTheLegacyPartialBudgetInference() {
        prefs.edit().putInt("boot", 7).putLong("use", 21L).putBoolean("desired", true)
            .putInt("retries", 1).putBoolean("retry_stopped", true)
            .putBoolean("retry_user_action", false).commit()

        assertEquals(SessionRecoveryRecord(21L, true, false, false, false, 1, true,
            retryRequiresUserAction = false), SessionRecoveryRecord.load(app))
    }

    @Test fun aDifferentBootCannotRestoreThePreviousParkingLockOrRetryBudget() {
        SessionRecoveryRecord.save(app, SessionRecoveryRecord(15L, false, true, true, false, 3, true))
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 8)
        assertNull(SessionRecoveryRecord.load(app))
    }

    @Test fun aConsumedAddressFamilyFallbackSurvivesOnlyTheSameBoot() {
        val switched = SessionRecoveryRecord(22L, true, false, false, false, 2, false,
            addressFamily = "IPv6", addressFamilySwitched = true)
        SessionRecoveryRecord.save(app, switched)
        assertEquals(switched, SessionRecoveryRecord.load(app))
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 8)
        assertNull(SessionRecoveryRecord.load(app))
    }

    @Test fun anUnavailableBootIdentityCannotAuthorizeRestoringPriorControlIntent() {
        SessionRecoveryRecord.save(app, SessionRecoveryRecord(15L, true, false, false, false, 3, false))
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
        assertNull(SessionRecoveryRecord.load(app))
        SessionRecoveryRecord.save(app, SessionRecoveryRecord(16L, false, false, true, false, 3, true))
        assertNull(SessionRecoveryRecord.load(app))
    }

    @Test fun unfinishedStartupDeadlineSurvivesTheSameBootAndCanBeClearedOnSuccess() {
        val connecting = SessionRecoveryRecord(23L, true, false, false, false, 2, false,
            startupDeadlineMillis = 123_456L)
        SessionRecoveryRecord.save(app, connecting)
        assertEquals(connecting, SessionRecoveryRecord.load(app))
        val connected = connecting.copy(startupDeadlineMillis = null)
        SessionRecoveryRecord.save(app, connected)
        assertEquals(connected, SessionRecoveryRecord.load(app))
        SessionRecoveryRecord.save(app, connecting)
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 8)
        assertNull(SessionRecoveryRecord.load(app))
    }
}
