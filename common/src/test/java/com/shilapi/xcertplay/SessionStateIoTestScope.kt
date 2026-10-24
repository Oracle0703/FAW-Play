package com.shilapi.xcertplay

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.robolectric.util.ReflectionHelpers

/** Existing lifecycle fixtures keep mocked construction on the owning test thread. */
internal class SessionStateIoTestScope : AutoCloseable {
    private val previous: java.util.concurrent.ExecutorService = ReflectionHelpers.getStaticField(
        CarPlayBackgroundSession::class.java, "stateIo")
    init { ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "stateIo", DirectSessionExecutor()) }
    override fun close() { ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "stateIo", previous) }
}
internal class DirectSessionExecutor : AbstractExecutorService() {
    private var stopped = false
    override fun execute(command: Runnable) { check(!stopped); command.run() }
    override fun shutdown() { stopped = true }
    override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
    override fun isShutdown() = stopped
    override fun isTerminated() = stopped
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
}
