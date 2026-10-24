package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AirPlayTrustPersistenceTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("xcertplay_airplay", 0)
    @Before fun reset() { prefs.edit().clear().commit() }
    @Test fun restoringPairingsDoesNotRewriteAndSkipsOnlyDamagedEntry() {
        val key = ByteArray(32) { it.toByte() }
        AirPlayPersistence.savePairing(app, "valid-phone", key)
        prefs.edit().putStringSet("pairing_ids", setOf("valid-phone", "broken-phone"))
            .putString("pairing.broken-phone", "broken").commit()
        val before = prefs.all.toMap(); var writes = 0
        val store = AirPlayPersistence.loadPairings(app) { _, _ -> writes++ }
        assertArrayEquals(key, store.get("valid-phone"))
        assertNull(store.get("broken-phone")); assertEquals(0, writes)
        assertEquals(before, prefs.all)
        store.save("valid-phone", key)
        assertEquals(0, writes)
        store.save("valid-phone", ByteArray(32) { 7 })
        assertEquals(1, writes)
    }
    @Test fun partialCorruptOrMismatchedIdentityNeverRotatesStoredTrust() {
        val identity = AirPlayPersistence.loadIdentity(app)
        val original = prefs.all.toMap()
        val changes: List<() -> Unit> = listOf(
            { prefs.edit().remove("identity_public").commit(); Unit },
            { prefs.edit().putString("identity_private", "xx".repeat(32)).commit(); Unit },
            { prefs.edit().putString("identity_public", "00".repeat(32)).commit(); Unit })
        changes.forEach { change ->
            prefs.edit().clear().putString("identity_private", original["identity_private"] as String)
                .putString("identity_public", original["identity_public"] as String)
                .putString("pairing_id", identity.pairingId).commit()
            change(); val before = prefs.all.toMap()
            try { AirPlayPersistence.loadIdentity(app); fail("damaged identity must stop") }
            catch (_: IllegalStateException) { }
            assertEquals(before, prefs.all)
        }
    }
    @Test fun missingIdentityWithPairingsOrDamagedIndexDoesNotGenerateNewKeys() {
        listOf<Any>(setOf("existing-phone"), "damaged-index").forEach { index ->
            prefs.edit().clear().commit()
            if (index is Set<*>) prefs.edit().putStringSet("pairing_ids", setOf("existing-phone")).commit()
            else prefs.edit().putString("pairing_ids", index as String).commit()
            val before = prefs.all.toMap()
            try { AirPlayPersistence.loadIdentity(app); fail("must preserve original trust boundary") }
            catch (_: IllegalStateException) { }
            assertEquals(before, prefs.all)
        }
    }
    @Test fun cleanInstallGeneratesOneStableValidIdentity() {
        val first = AirPlayPersistence.loadIdentity(app)
        val second = AirPlayPersistence.loadIdentity(app)
        assertEquals(32, first.privateKey.size); assertEquals(32, first.publicKey.size)
        assertArrayEquals(first.privateKey, second.privateKey)
        assertArrayEquals(first.publicKey, second.publicKey)
        assertEquals(first.pairingId, second.pairingId)
    }
}
