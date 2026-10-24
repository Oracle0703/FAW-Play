package com.shilapi.xcertplay.network

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.net.Socket

/** Detect a vanished peer, without treating a legitimately idle CarPlay screen as a failure. */
internal object TcpLiveness {
    // Linux UAPI <linux/tcp.h>; Android's SDK omits these three OsConstants fields.
    private const val TCP_KEEPIDLE = 4
    private const val TCP_KEEPINTVL = 5
    private const val TCP_KEEPCNT = 6
    fun configure(socket: Socket, fast: Boolean = false, diagnostic: (String) -> Unit) {
        try {
            socket.keepAlive = true
            diagnostic("TCP peer health keepAlive=${socket.keepAlive}")
            if (Build.VERSION.SDK_INT >= 29) {
                ParcelFileDescriptor.fromSocket(socket).use { duplicate -> tune(duplicate.fileDescriptor, fast, diagnostic) }
            } else {
                // Android 9 fromSocket shares the Socket's FileDescriptor: closing or detaching
                // that wrapper would close/invalidate the live socket. Borrow the platform FD
                // and duplicate it ourselves; never transfer ownership of the original.
                val borrowed = Socket::class.java.getMethod("getFileDescriptor$").invoke(socket) as FileDescriptor
                val duplicate = Os.dup(borrowed)
                try { tune(duplicate, fast, diagnostic) } finally { Os.close(duplicate) }
            }
        } catch (failure: Exception) {
            diagnostic("TCP peer health tuning unavailable: ${failure.javaClass.simpleName}")
        }
    }

    private fun tune(fd: FileDescriptor, fast: Boolean, diagnostic: (String) -> Unit) = configureOptions(
        fast,
        set = { option, value -> Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, option, value) },
        // getsockoptInt is hidden from the Android SDK. Some firmware permits this diagnostic
        // access; when it does not, report readbackUnavailable rather than assuming success.
        read = { option ->
            Os::class.java.getMethod("getsockoptInt", FileDescriptor::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(null, fd, OsConstants.IPPROTO_TCP, option) as Int
        },
        diagnostic = diagnostic,
    )

    /** Each option degrades independently; only kernel readback is reported as effective. */
    internal fun configureOptions(
        fast: Boolean,
        set: (Int, Int) -> Unit,
        read: (Int) -> Int,
        diagnostic: (String) -> Unit,
    ) {
        val options = listOf(
            Triple("idleSeconds", TCP_KEEPIDLE, if (fast) 1 else 2),
            Triple("intervalSeconds", TCP_KEEPINTVL, 1),
            Triple("probeCount", TCP_KEEPCNT, if (fast) 2 else 3),
            Triple("userTimeoutMillis", OsConstants.TCP_USER_TIMEOUT, if (fast) 3_000 else 5_000),
        )
        for ((name, option, requested) in options) {
            try {
                set(option, requested)
            } catch (failure: Exception) {
                diagnostic("TCP peer health $name requested=$requested unavailable=${failure.javaClass.simpleName}")
                continue
            }
            try {
                val effective = read(option)
                diagnostic("TCP peer health $name requested=$requested effective=$effective")
            } catch (failure: Exception) {
                diagnostic("TCP peer health $name requested=$requested readbackUnavailable=${failure.javaClass.simpleName}")
            }
        }
    }
}
