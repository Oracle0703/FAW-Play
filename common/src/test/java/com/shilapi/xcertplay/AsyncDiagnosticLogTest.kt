package com.shilapi.xcertplay

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AsyncDiagnosticLogTest {
    @Test fun blockedDiskKeepsOpenAndCloseNonblockingAndLifecycleAdmissionBounded() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        @Suppress("UNCHECKED_CAST")
        val writer = AsyncDiagnosticLog::class.java.getDeclaredField("writer").apply { isAccessible = true }
            .get(AsyncDiagnosticLog) as BoundedDiagnosticWriter<() -> Unit>
        writer.enqueueControl { entered.countDown(); release.await() }
        val folder = Files.createTempDirectory("faw-slow-log-lifecycle").toFile()
        val targets = mutableListOf<SessionLogFile>()
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            repeat(8) { index ->
                targets.add(AsyncDiagnosticLog.open(folder.resolve("session-$index/log"), "session")!!)
            }
            assertNull(AsyncDiagnosticLog.open(folder.resolve("overflow/log"), "session"))
            targets.forEach { AsyncDiagnosticLog.close(it) }
            assertFalse(folder.resolve("session-0/log").exists())
            assertFalse(AsyncDiagnosticLog.flush(5))
        } finally { release.countDown(); assertTrue(AsyncDiagnosticLog.flush(2_000)); folder.deleteRecursively() }
    }

    @Test fun newSessionRotatesOnlyAfterClosingThePreviousPhysicalTarget() {
        val folder = Files.createTempDirectory("diplay-session-order").toFile()
        val file = folder.resolve("diplay.log")
        val old = AsyncDiagnosticLog.open(file, "old session")!!
        AsyncDiagnosticLog.append(old, "old evidence")
        val current = AsyncDiagnosticLog.open(file, "new session")!!
        AsyncDiagnosticLog.append(old, "late old callback")
        AsyncDiagnosticLog.append(current, "current evidence")
        AsyncDiagnosticLog.close(old, "old closed")
        AsyncDiagnosticLog.close(current, "new closed")
        assertTrue(AsyncDiagnosticLog.flush(2_000))
        val currentText = file.readText()
        assertTrue(currentText.contains("current evidence"))
        assertFalse(currentText.contains("old evidence"))
        assertFalse(currentText.contains("late old callback"))
        assertTrue(folder.resolve("previous.log").readText().contains("old evidence"))
        folder.deleteRecursively()
    }
    @Test fun queuedEvidenceIsRedactedAndTimestampedBeforeUsingItsCapturedFileTarget() {
        val folder = Files.createTempDirectory("diplay-async-log").toFile()
        val old = SessionLogFile(folder.resolve("old.log"))
        val current = SessionLogFile(folder.resolve("current.log"))
        try {
            old.reset("old session"); current.reset("current session")
            AsyncDiagnosticLog.append(old, "CONNECTION_DIAGNOSTIC attempt=1 teardown end elapsedMs=117", 0)
            AsyncDiagnosticLog.append(old, "Microphone: start type=telephony codec=OPUS peer=192.168.49.1", 0)
            AsyncDiagnosticLog.append(old, "CONNECTION_DIAGNOSTIC token=private-token", 0)
            AsyncDiagnosticLog.append(old, "Microphone: payload=private-audio", 0)
            assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
            val evidence = old.file.readText()
            assertTrue(evidence.contains("teardown end elapsedMs=117"))
            assertTrue(evidence.contains("Microphone: start type=telephony codec=OPUS peer=[ip]"))
            assertFalse(evidence.contains("192.168.49.1"))
            assertFalse(evidence.contains("private-token")); assertFalse(evidence.contains("private-audio"))
            assertFalse(current.file.readText().contains("teardown end"))
            assertEquals(4, evidence.lineSequence().count())
        } finally {
            old.close(); current.close(); folder.deleteRecursively()
        }
    }
}
