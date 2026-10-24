package com.shilapi.xcertplay.orchestration

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayLastUserModeTest {
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("last_mode_test", 0)

    @Test fun reopeningRetainsIndependentChoicesWithoutSavingThePhoneIdentity() {
        prefs.edit().clear().commit()
        val owner = Any()
        val modes = CarPlayLastUserMode(prefs)
        modes.begin(owner, "synthetic-phone-one")
        modes.screen(owner, true); modes.audio(owner, false); modes.playback(owner, false)
        val reopened = CarPlayLastUserMode(prefs)
        assertEquals(CarPlayLastUserMode.Mode(true, false, false), reopened.begin(Any(), "synthetic-phone-one"))
        assertFalse(prefs.all.toString().contains("synthetic-phone"))
        assertEquals(CarPlayLastUserMode.Mode(), reopened.begin(Any(), "synthetic-phone-two"))
    }

    @Test fun teardownAndOldSessionsCannotOverwriteTheNextTrip() {
        prefs.edit().clear().commit()
        val first = Any(); val second = Any()
        val modes = CarPlayLastUserMode(prefs)
        modes.begin(first, "one"); modes.audio(first, true); modes.playback(first, true)
        modes.end(first); modes.playback(first, false)
        assertEquals(CarPlayLastUserMode.Mode(audio = true, playing = true), modes.begin(second, "one"))
        modes.playback(first, false); modes.end(first)
        modes.screen(second, false)
        assertEquals(CarPlayLastUserMode.Mode(false, true, true), CarPlayLastUserMode(prefs).begin(Any(), "one"))
    }

    @Test fun anonymousSessionsCannotCreatePersistentChoices() {
        prefs.edit().clear().commit()
        val owner = Any(); val modes = CarPlayLastUserMode(prefs)
        modes.begin(owner, null); modes.audio(owner, true)
        assertTrue(prefs.all.isEmpty())
    }
}
