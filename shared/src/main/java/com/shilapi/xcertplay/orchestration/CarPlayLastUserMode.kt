package com.shilapi.xcertplay.orchestration

import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * Remembers explicit choices per authenticated phone, independently of temporary borrowing.
 * A saved playing bit is intent, not proof that the vehicle source stayed unchanged offline.
 */
internal class CarPlayLastUserMode(private val preferences: SharedPreferences) {
    data class Mode(val screen: Boolean? = null, val audio: Boolean? = null, val playing: Boolean? = null)
    private var session: Any? = null
    private var key: String? = null
    private var mode = Mode()

    @Synchronized fun begin(owner: Any, authenticatedPhone: String?): Mode {
        session = owner
        key = authenticatedPhone?.takeIf { it.isNotBlank() }?.let {
            "v1_" + MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }
        val saved = key?.let { preferences.getString(it, null) }
        fun flag(index: Int): Boolean? = when (saved?.getOrNull(index)) { '1' -> true; '0' -> false; else -> null }
        mode = if (saved?.length == 3 && saved.all { it in "10-" }) Mode(flag(0), flag(1), flag(2)) else Mode()
        return mode
    }

    @Synchronized fun screen(owner: Any, selected: Boolean) = update(owner) { it.copy(screen = selected) }
    @Synchronized fun audio(owner: Any, selected: Boolean) = update(owner) { it.copy(audio = selected) }
    @Synchronized fun playback(owner: Any, playing: Boolean) = update(owner) { it.copy(playing = playing) }
    @Synchronized fun end(owner: Any) { if (session === owner) { session = null; key = null } }

    private fun update(owner: Any, change: (Mode) -> Mode) {
        if (session !== owner) return
        val next = change(mode)
        if (next == mode) return
        mode = next
        val recordKey = key ?: return
        fun flag(value: Boolean?) = when (value) { true -> "1"; false -> "0"; null -> "-" }
        preferences.edit().putString(recordKey, flag(next.screen) + flag(next.audio) + flag(next.playing)).apply()
    }
}
