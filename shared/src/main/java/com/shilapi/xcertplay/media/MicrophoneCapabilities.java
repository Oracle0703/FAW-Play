package com.shilapi.xcertplay.media;

import android.media.MediaCodecList;
import android.media.MediaFormat;

/** A decoder does not imply that the same device can encode microphone audio. */
public final class MicrophoneCapabilities {
    private MicrophoneCapabilities() {}

    public static boolean supportsOpusEncoding() {
        try {
            MediaFormat format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 1);
            return new MediaCodecList(MediaCodecList.ALL_CODECS)
                    .findEncoderForFormat(format) != null || NativeOpusEncoder.isAvailable();
        } catch (RuntimeException | LinkageError unavailable) {
            // Try the bundled encoder when the platform cannot establish an
            // Opus encoder capability. PCM remains a last-resort advertised format.
            return NativeOpusEncoder.isAvailable();
        }
    }
}
