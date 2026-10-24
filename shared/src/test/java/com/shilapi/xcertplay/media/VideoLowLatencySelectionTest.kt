package com.shilapi.xcertplay.media

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class VideoLowLatencySelectionTest {
    @Before fun setup() { ShadowMediaCodecList.reset() }
    @After fun cleanup() { ShadowMediaCodecList.reset() }

    @Test fun onlyAnAdvertisedHardwareDecoderSupportingTheCodedSizeIsSelected() {
        add("c2.default", hardware = true, lowLatency = false)
        add("c2.software", hardware = false, lowLatency = true)
        add("c2.small", hardware = true, lowLatency = true, width = 640, height = 480)
        add("c2.low_latency", hardware = true, lowLatency = true)
        assertEquals("c2.low_latency", selected())
    }

    @Test fun unsupportedLowLatencyFallsBackToTheOrdinaryDecoderPath() {
        add("c2.default", hardware = true, lowLatency = false)
        assertNull(selected())
    }

    private fun selected(): String? {
        val sink = AndroidMediaSink()
        sink.prepareVideoFrame(110)
        val decoders = sink.javaClass.getDeclaredField("videoDecoders").apply { isAccessible = true }.get(sink) as Map<*, *>
        val worker = decoders[110]!!
        return try {
            worker.javaClass.getDeclaredMethod("lowLatencyDecoderName", String::class.java, VideoParameters::class.java)
                .apply { isAccessible = true }.invoke(worker, MediaFormat.MIMETYPE_VIDEO_AVC, VideoParameters(1280, 720, -1, -1, -1)) as String?
        } finally {
            sink.close()
            (worker.javaClass.getDeclaredField("thread").apply { isAccessible = true }.get(worker) as Thread).join(1_000)
        }
    }

    private fun add(name: String, hardware: Boolean, lowLatency: Boolean, width: Int = 1920, height: Int = 1080) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            .apply { setFeatureEnabled("low-latency", lowLatency) }
        val level = MediaCodecInfo.CodecProfileLevel().apply {
            profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
            this.level = MediaCodecInfo.CodecProfileLevel.AVCLevel52
        }
        val capabilities = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder().setMediaFormat(format)
            .setProfileLevels(arrayOf(level)).setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName(name).setIsEncoder(false)
            .setIsHardwareAccelerated(hardware).setIsSoftwareOnly(!hardware).setCapabilities(capabilities).build())
    }
}
