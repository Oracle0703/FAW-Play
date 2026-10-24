package com.shilapi.xcertplay

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig

/**
 * Full-screen CarPlay host. It renders decoded video through a [VideoSurfaceView], forwards touch to
 * the active AirPlay session. [CarPlayBackgroundSession] owns connection startup and recovery.
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
internal data class CanvasSupport(val supported: Boolean, val reason: String, val details: String)

/** API28-compatible decoder capability checks, independent of Activity/window lifetime. */
internal class ProjectionDecoderCapabilities(
    private val hevcEnabled: Boolean,
    private val hevcSoftwareDecoderEnabled: Boolean,
) {
    fun evaluate(display: AirPlayDisplayConfig): CanvasSupport =
        if (maxOf(display.widthPixels, display.heightPixels) > MAX_ENLARGED_CANVAS_LONG_SIDE ||
            minOf(display.widthPixels, display.heightPixels) > MAX_ENLARGED_CANVAS_SHORT_SIDE) {
            CanvasSupport(false, "canvas_4k_limit", "Decoder capability check skipped: canvas exceeds enlargement limit")
        } else decoderCanvasSupport(display)

    private fun decoderCanvasSupport(display: AirPlayDisplayConfig): CanvasSupport {
        return try {
            val mime = if (hevcEnabled) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            // Match MediaCodec's first decoder; enlargement must not silently select software.
            val decoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) }
            } ?: return CanvasSupport(false, "no_decoder", "Decoder capability mime=$mime result=no_decoder")

            val hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                decoder.isHardwareAccelerated
            } else {
                !decoder.name.startsWith("OMX.google.") && !decoder.name.startsWith("c2.android.")
            }
            val video = decoder.getCapabilitiesForType(mime).videoCapabilities
            val sizeSupported = video?.isSizeSupported(display.widthPixels, display.heightPixels) == true
            val rateSupported = sizeSupported && video?.areSizeAndRateSupported(
                display.widthPixels,
                display.heightPixels,
                display.fps.toDouble(),
            ) == true
            val reason = when {
                !hardware -> "software_decoder"
                hevcEnabled && hevcSoftwareDecoderEnabled -> "software_hevc_selected"
                video == null -> "no_video_capabilities"
                !sizeSupported -> "canvas_dimensions_unsupported"
                !rateSupported -> "frame_rate_unsupported"
                else -> "supported"
            }
            CanvasSupport(
                reason == "supported",
                reason,
                "Decoder capability codec=${decoder.name} mime=$mime hardware=$hardware " +
                    "sizeSupported=$sizeSupported rateSupported=$rateSupported " +
                    "widths=${video?.supportedWidths} heights=${video?.supportedHeights} " +
                    "alignment=${video?.widthAlignment}x${video?.heightAlignment} " +
                    "fpsRange=${video?.supportedFrameRates} result=$reason",
            )
        } catch (error: Exception) {
            CanvasSupport(
                false,
                "capability_query_${error.javaClass.simpleName}",
                "Decoder capability query failed error=${error.javaClass.simpleName}",
            )
        }
    }

    private companion object {
        const val MAX_ENLARGED_CANVAS_LONG_SIDE = 3840
        const val MAX_ENLARGED_CANVAS_SHORT_SIDE = 2160
    }
}
