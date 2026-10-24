package com.shilapi.xcertplay.media

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.media.VolumeShaper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException

/** AudioTrack's attributes getter is only available from Android 10. */
internal fun audioTrackAttributesForFocus(track: AudioTrack, configured: AudioAttributes): AudioAttributes =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) track.audioAttributes else configured

/** Owns one focus request for all eligible tracks in a CarPlay sink. */
internal class AudioFocusCoordinator(
    context: Context?,
    private val enabled: Boolean,
    private val report: (String) -> Unit = {},
    private val onNativeMainAudioFocusChanged: (Int) -> Unit = {},
) {
    private data class Entry(
        val channel: AudioChannel,
        val attributes: AudioAttributes,
        var navigationGain: Float = FULL_VOLUME,
        var navigationFade: VolumeShaper? = null,
        var focusVolume: Float? = null,
    )

    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val automotive = context?.packageManager
        ?.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) == true
    private val hongqiPhoneFocusCompatibility =
        Build.VERSION.SDK_INT == Build.VERSION_CODES.P && Build.MODEL == "HONG QI"
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private class FocusState {
        var request: AudioFocusRequest? = null
        var channel: AudioChannel? = null
        var attributes: AudioAttributes? = null
        var result = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        var change = AudioManager.AUDIOFOCUS_LOSS
        var callbackReceived = false
        var failures = 0
        var retryAt = 0L
        var generation = 0L
        var nativeBorrowed = false
    }
    private val transientFocus = FocusState()
    private val mediaFocus = FocusState()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var closed = false
    private var mediaSourceSelected = false
    private var mainAudioAllowed = true
    private var phoneActive = false
    private val selectedMediaSource by lazy {
        Entry(AudioChannel.MEDIA, AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
    }
    private val retry = Runnable {
        synchronized(this) {
            if (!closed) refreshRequest()
        }
    }

    @Synchronized
    fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes) {
        if (closed) return
        active.putIfAbsent(track, Entry(channel, attributes))
        refreshNavigationVolume()
        refreshRequest()
    }

    @Synchronized
    fun setMainAudioAllowed(allowed: Boolean) {
        if (closed || mainAudioAllowed == allowed) return
        mainAudioAllowed = allowed
        if (!allowed) mediaSourceSelected = false
        refreshRequest()
    }

    /** Phone state can arrive before its AudioTrack is registered. */
    @Synchronized
    fun setPhoneActive(active: Boolean) {
        if (!closed) phoneActive = active
    }

    /** Bounded state metadata for the same five-second window as the output counters. */
    @Synchronized
    fun reportPhoneOutputState(track: AudioTrack) {
        val entry = active[track]?.takeIf { it.channel == AudioChannel.PHONE } ?: return
        val state = transientFocus
        runCatching { report("Audio: call state gain=${entry.focusVolume} requestResult=${state.result} " +
            "focusChange=${state.change} callbackReceived=${state.callbackReceived} mainAudioAllowed=$mainAudioAllowed " +
            "phoneFocusCompatibility=$hongqiPhoneFocusCompatibility") }
        val audio = manager ?: return
        runCatching { report("Audio: call volume mode=${audio.mode} speakerphone=${audio.isSpeakerphoneOn} " +
            "bluetoothSco=${audio.isBluetoothScoOn} voiceVolume=${audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)} " +
            "voiceMaxVolume=${audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)} " +
            "voiceMuted=${audio.isStreamMute(AudioManager.STREAM_VOICE_CALL)} " +
            "mediaVolume=${audio.getStreamVolume(AudioManager.STREAM_MUSIC)} " +
            "mediaMuted=${audio.isStreamMute(AudioManager.STREAM_MUSIC)}") }
    }

    /** An explicit audio selection or playback start may select CarPlay; page entry must not. */
    @Synchronized
    fun selectMediaSource() {
        if (closed || !mainAudioAllowed || !enabled || manager == null) return
        mediaSourceSelected = true
        val state = if (automotive) mediaFocus else transientFocus
        // Never replace the call/Siri request just because the user opened the CarPlay screen.
        if (state.channel == AudioChannel.MEDIA && state.change == AudioManager.AUDIOFOCUS_LOSS)
            abandon(state)
        refreshRequest()
    }

    @Synchronized
    fun release(track: AudioTrack) {
        active.remove(track)?.let { entry ->
            runCatching { entry.navigationFade?.close() }
            refreshNavigationVolume()
            refreshRequest()
        }
    }

    @Synchronized
    fun close() {
        closed = true
        if (enabled && manager != null) handler.removeCallbacks(retry)
        active.values.forEach { runCatching { it.navigationFade?.close() } }
        active.clear()
        val failures = mutableListOf<Exception>()
        listOf(transientFocus, mediaFocus).forEach { state ->
            try { abandon(state, strict = true) }
            catch (error: Exception) { failures.add(error) }
        }
        if (failures.isNotEmpty()) throw IOException("Audio focus release is incomplete", failures.first())
    }

    private fun abandon(state: FocusState, strict: Boolean = false) {
        state.generation++
        state.request?.let { request ->
            try { manager?.abandonAudioFocusRequest(request) }
            catch (error: Exception) { if (strict) throw error }
        }
        state.request = null
        state.channel = null
        state.attributes = null
        state.failures = 0
        state.retryAt = 0
        state.result = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        state.change = AudioManager.AUDIOFOCUS_LOSS
        state.callbackReceived = false
        state.nativeBorrowed = false
    }

    private fun refreshRequest() {
        if (!enabled || manager == null) { applyFocusVolumes(); return }
        val eligible = active.values.filter { (mainAudioAllowed || it.channel == AudioChannel.NAVIGATION) &&
            (automotive || it.channel != AudioChannel.NAVIGATION) }
        // Keep the vehicle's music source underneath temporary navigation/call focus.
        if (automotive) refresh(mediaFocus, eligible.firstOrNull { it.channel == AudioChannel.MEDIA }
            ?: selectedMediaSource.takeIf { mediaSourceSelected })
        val primary = eligible.filter { !automotive || it.channel != AudioChannel.MEDIA }
            .maxByOrNull { it.channel.focusPriority() }
        refresh(transientFocus, primary ?: selectedMediaSource.takeIf { !automotive && mediaSourceSelected })
        applyFocusVolumes()
        handler.removeCallbacks(retry)
        if (closed) return
        listOf(mediaFocus, transientFocus).filter {
            it.request != null && it.result == AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }.minOfOrNull { it.retryAt }?.let {
            handler.postDelayed(retry, (it - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1))
        }
    }

    private fun refresh(state: FocusState, entry: Entry?) {
        // A temporary OEM interruption may pause/stop the stream. Keep its existing focus
        // listener until GAIN, so we can return the borrowed resource without requesting focus.
        if (entry == null && state.nativeBorrowed && !closed) return
        if (entry == null || closed) { abandon(state); return }
        val same = state.request != null && state.channel == entry.channel && state.attributes == entry.attributes
        val now = android.os.SystemClock.uptimeMillis()
        if (same && (state.result != AudioManager.AUDIOFOCUS_REQUEST_FAILED || now < state.retryAt)) return
        if (!same) abandon(state)
        val gain = when (entry.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT, AudioChannel.NAVIGATION -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        val generation = state.generation
        val next = state.request ?: AudioFocusRequest.Builder(gain)
            .setAudioAttributes(entry.attributes)
            .setOnAudioFocusChangeListener({ change ->
                synchronized(this) {
                    if (closed || generation != state.generation) return@synchronized
                    runCatching { report("Audio: focus change=$change channel=${entry.channel} activeTracks=${active.size}") }
                    // A granted request losing focus must not start a failed-request retry loop.
                    state.callbackReceived = true
                    state.change = change
                    if (change == AudioManager.AUDIOFOCUS_GAIN) state.result = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    applyFocusVolumes()
                    if (entry.channel == AudioChannel.MEDIA && (mainAudioAllowed || state.nativeBorrowed)) {
                        val voiceActive = phoneActive || active.values.any {
                            it.channel == AudioChannel.PHONE || it.channel == AudioChannel.ASSISTANT
                        }
                        // LOSS does not identify a user source selection. It still silences
                        // this track locally, but must never become a permanent CarPlay TAKE.
                        if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && !voiceActive) {
                            state.nativeBorrowed = true
                            onNativeMainAudioFocusChanged(change)
                        } else if (change == AudioManager.AUDIOFOCUS_GAIN && state.nativeBorrowed) {
                            state.nativeBorrowed = false
                            onNativeMainAudioFocusChanged(change)
                        }
                    }
                }
            }, handler).build()
        state.request = next
        state.channel = entry.channel
        state.attributes = entry.attributes
        state.result = runCatching { manager?.requestAudioFocus(next) }.getOrNull()
            ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        state.change = if (state.result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            AudioManager.AUDIOFOCUS_GAIN else AudioManager.AUDIOFOCUS_LOSS
        if (state.result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
            val delay = RETRY_DELAYS[state.failures.coerceAtMost(RETRY_DELAYS.lastIndex)]
            state.failures++
            state.retryAt = android.os.SystemClock.uptimeMillis() + delay
        } else {
            state.failures = 0
            state.retryAt = 0
        }
        val line = "Audio: focus requested channel=${entry.channel} gain=$gain granted=${state.result} activeTracks=${active.size}"
        Log.i(TAG, line)
        runCatching { report(line) }
    }

    private fun refreshNavigationVolume() {
        if (!automotive) return
        val gain = if (active.values.any { it.channel == AudioChannel.NAVIGATION }) NAVIGATION_MUSIC_GAIN else FULL_VOLUME
        active.forEach { (track, entry) ->
            if (entry.channel != AudioChannel.MEDIA || entry.navigationGain == gain) return@forEach
            val duration = if (gain < entry.navigationGain) NAVIGATION_FADE_DOWN_MILLIS else NAVIGATION_FADE_UP_MILLIS
            runCatching {
                val previous = entry.navigationFade
                val current = previous?.volume ?: FULL_VOLUME
                val curve = VolumeShaper.Configuration.Builder(VolumeShaper.Configuration.SCURVE_RAMP)
                    .scaleToStartVolume(current)
                    .scaleToEndVolume(gain)
                    .setDuration(duration)
                    .build()
                if (previous == null) {
                    entry.navigationFade = track.createVolumeShaper(curve).also { it.apply(VolumeShaper.Operation.PLAY) }
                } else {
                    // Join from the current gain when prompts arrive during a recovery fade.
                    previous.replace(curve, VolumeShaper.Operation.PLAY, true)
                }
                entry.navigationGain = gain
                val line = "Audio: navigation music transition target=$gain durationMs=$duration"
                Log.i(TAG, line)
                runCatching { report(line) }
            }.onFailure { error ->
                runCatching { report("Audio: navigation music transition unavailable ${error.javaClass.simpleName}") }
            }
        }
    }

    private fun applyFocusVolumes() {
        active.forEach { (track, entry) ->
            val state = if (automotive && entry.channel == AudioChannel.MEDIA) mediaFocus else transientFocus
            val volume = when {
                !mainAudioAllowed && entry.channel != AudioChannel.NAVIGATION -> 0f
                !enabled || manager == null || (!automotive && entry.channel == AudioChannel.NAVIGATION) -> FULL_VOLUME
                // Pre-brand FAWPlay kept PHONE output audible after an initial rejection
                // on this head unit. Keep retrying, but still honor actual loss callbacks.
                hongqiPhoneFocusCompatibility && entry.channel == AudioChannel.PHONE &&
                    state.result == AudioManager.AUDIOFOCUS_REQUEST_FAILED && !state.callbackReceived -> FULL_VOLUME
                else -> when (state.change) {
                AudioManager.AUDIOFOCUS_GAIN -> FULL_VOLUME
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                    if (!automotive && entry.channel == AudioChannel.MEDIA) DUCKED_VOLUME else FULL_VOLUME
                else -> 0f
                }
            }
            if (entry.focusVolume == volume) return@forEach
            // Keep consuming audio while silent: a later GAIN must resume current sound, not a stale queue.
            // Static focus gain multiplies the independent OEM navigation VolumeShaper curve.
            runCatching { track.setStereoVolume(volume, volume) }.onSuccess {
                entry.focusVolume = volume
                runCatching { report("Audio: focus volume channel=${entry.channel} gain=$volume " +
                    "requestResult=${state.result} focusChange=${state.change} callbackReceived=${state.callbackReceived} " +
                    "mainAudioAllowed=$mainAudioAllowed") }
            }
        }
    }

    private fun AudioChannel.focusPriority(): Int = when (this) {
        AudioChannel.PHONE -> 4
        AudioChannel.ASSISTANT -> 3
        AudioChannel.NAVIGATION -> 2
        AudioChannel.MEDIA -> 1
    }

    private companion object {
        val RETRY_DELAYS = longArrayOf(1_000, 2_000, 5_000, 15_000, 30_000)
        const val TAG = "DiPlay-AudioFocus"
        const val FULL_VOLUME = 1f
        const val DUCKED_VOLUME = 0.2f
        const val NAVIGATION_MUSIC_GAIN = 0.75f
        const val NAVIGATION_FADE_DOWN_MILLIS = 400L
        const val NAVIGATION_FADE_UP_MILLIS = 800L
    }
}
