package com.shilapi.xcertplay.updates

import com.shilapi.xcertplay.api.HttpRequestCancelledException
import com.shilapi.xcertplay.api.HttpRequestControl
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloadFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val hash = "a".repeat(64)

    @Test fun reclaimsOnlyRecognizedUnownedPartsAndPreservesActiveReadyAndUnrelatedFiles() {
        val directory = temporary.newFolder()
        val active = UpdateDownloadFiles.create(directory, hash)
        try {
            val legacy = File(directory, "$hash.apk.part").apply { writeText("interrupted legacy request") }
            val orphan = File(directory, "$hash.${UUID.randomUUID()}.apk.part").apply { writeText("interrupted request") }
            val ready = File(directory, "$hash.apk").apply { writeText("ready package") }
            val envelope = File(directory, "manifest-envelope.json").apply { writeText("signed envelope") }
            val unrelated = File(directory, "notes.apk.part").apply { writeText("unrelated") }
            val folder = File(directory, "${"b".repeat(64)}.apk.part").apply { mkdir() }
            assertEquals(2, UpdateDownloadFiles.cleanupOrphans(directory))
            assertFalse(legacy.exists())
            assertFalse(orphan.exists())
            for (preserved in listOf(active, ready, envelope, unrelated, folder)) assertTrue(preserved.exists())
        } finally { UpdateDownloadFiles.release(active) }
    }

    @Test fun simultaneousDownloadsOfTheSameHashHaveIndependentTemporaryOwners() {
        val directory = temporary.newFolder()
        val first = UpdateDownloadFiles.create(directory, hash)
        val second = UpdateDownloadFiles.create(directory, hash)
        try {
            first.writeText("first")
            second.writeText("second")
            assertNotEquals(first, second)
            assertEquals(0, UpdateDownloadFiles.cleanupOrphans(directory))
            UpdateDownloadFiles.release(first)
            assertFalse(first.exists())
            assertEquals("second", second.readText())
            assertEquals(0, UpdateDownloadFiles.cleanupOrphans(directory))
        } finally {
            UpdateDownloadFiles.release(first)
            UpdateDownloadFiles.release(second)
        }
    }

    @Test fun cancellationCannotReplaceAReadyPackageAndFinalCleanupRemovesOnlyItsOwnPart() {
        val directory = temporary.newFolder()
        val ready = File(directory, "$hash.apk").apply { writeText("existing package") }
        val part = UpdateDownloadFiles.create(directory, hash)
        val control = HttpRequestControl().apply { cancel() }
        try {
            part.writeText("new package")
            assertThrows(HttpRequestCancelledException::class.java) { UpdateDownloadFiles.publish(part, ready, control) }
            assertEquals("existing package", ready.readText())
        } finally { UpdateDownloadFiles.release(part) }
        assertFalse(part.exists())
        assertTrue(ready.exists())
    }

    @Test fun publicationCannotUseAnUnownedFileAndCleanupNeverDeletesThePublishedApk() {
        val directory = temporary.newFolder()
        val unowned = File(directory, "$hash.apk.part").apply { writeText("unowned") }
        val ready = File(directory, "$hash.apk")
        assertThrows(IllegalStateException::class.java) { UpdateDownloadFiles.publish(unowned, ready, HttpRequestControl()) }
        assertTrue(unowned.exists())
        ready.writeText("previous ready package")
        val part = UpdateDownloadFiles.create(directory, hash)
        try {
            part.writeText("verified by the download owner")
            assertEquals(ready, UpdateDownloadFiles.publish(part, ready, HttpRequestControl()))
        } finally { UpdateDownloadFiles.release(part) }
        assertEquals("verified by the download owner", ready.readText())
        assertEquals(0, UpdateDownloadFiles.cleanupOrphans(directory))
    }
}
