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
    fun configure(socket: Socket, diagnostic: (String) -> Unit) {
        try {
            socket.keepAlive = true
            if (Build.VERSION.SDK_INT >= 29) {
                ParcelFileDescriptor.fromSocket(socket).use { duplicate -> tune(duplicate.fileDescriptor) }
            } else {
                // Android 9 fromSocket shares the Socket's FileDescriptor: closing or detaching
                // that wrapper would close/invalidate the live socket. Borrow the platform FD
                // and duplicate it ourselves; never transfer ownership of the original.
                val borrowed = Socket::class.java.getMethod("getFileDescriptor$").invoke(socket) as FileDescriptor
                val duplicate = Os.dup(borrowed)
                try { tune(duplicate) } finally { Os.close(duplicate) }
            }
            diagnostic("TCP peer health enabled idle=2s interval=1s count=3 deadline=5000ms; no video-idle timeout")
        } catch (failure: Exception) {
            diagnostic("TCP peer health tuning unavailable: ${failure.javaClass.simpleName}")
        }
    }

    private fun tune(fd: FileDescriptor) {
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, 2)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, 1)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, 3)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_USER_TIMEOUT, 5_000)
    }
}
