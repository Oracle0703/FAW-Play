package com.shilapi.xcertplay

import android.content.Context
import android.graphics.BitmapFactory
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.host.R

/**
 * Full-screen CarPlay host. It renders decoded video through a [VideoSurfaceView], forwards touch to
 * the active AirPlay session. [CarPlayBackgroundSession] owns connection startup and recovery.
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
/** Validates the selected CarPlay icon without retaining the projection Activity. */
internal class AirPlayIconRepository(private val context: Context) {
    fun load(): AirPlayIcon {
        val customBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(context)?.readBytes()
        } catch (_: Exception) {
            null
        }
        if (customBytes != null) {
            decodeAirPlayIcon(customBytes)?.let { return it }
            AirPlayPersistence.clearCustomAirPlayIcon(context)
        }
        return decodeAirPlayIcon(defaultAirPlayIconBytes())
            ?: throw IllegalStateException("Packaged AirPlay icon is invalid")
    }

    private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth != bounds.outHeight
        ) {
            return null
        }
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    private fun defaultAirPlayIconBytes(): ByteArray =
        // Shown in CarPlay's app list as the "back to the car" button.
        context.resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }

}
