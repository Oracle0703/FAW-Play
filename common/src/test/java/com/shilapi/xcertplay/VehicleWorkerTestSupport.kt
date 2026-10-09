package com.shilapi.xcertplay

import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.robolectric.Shadows.shadowOf

internal fun settleVehicleWorker() {
    val worker = HongqiWheelControls::class.java.getDeclaredField("WORKER")
        .apply { isAccessible = true }.get(null) as ExecutorService
    repeat(4) {
        shadowOf(Looper.getMainLooper()).idle()
        worker.submit {}.get(2, TimeUnit.SECONDS)
    }
    shadowOf(Looper.getMainLooper()).idle()
}
