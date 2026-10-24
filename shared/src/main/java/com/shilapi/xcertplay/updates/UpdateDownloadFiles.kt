package com.shilapi.xcertplay.updates

import com.shilapi.xcertplay.api.HttpRequestControl
import java.io.File
import java.util.UUID

/** Process-local owners protect active downloads; only our unowned temporary names are reclaimed. */
internal object UpdateDownloadFiles {
    private val lock = Any()
    private val active = HashSet<String>()
    private val partialName = Regex("[0-9a-f]{64}(\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?\\.apk\\.part")

    fun cleanupOrphans(directory: File): Int = synchronized(lock) { cleanupLocked(directory) }

    fun create(directory: File, hash: String): File = synchronized(lock) {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw UpdateException("无法创建更新包目录。")
        cleanupLocked(directory)
        val file = File(directory, "$hash.${UUID.randomUUID()}.apk.part")
        if (!file.createNewFile()) throw UpdateException("无法创建更新包暂存文件。")
        active.add(file.absolutePath)
        file
    }

    fun publish(partial: File, ready: File, control: HttpRequestControl): File = synchronized(lock) {
        check(partial.absolutePath in active)
        control.ensureActive()
        // Android replaces the old pathname atomically. If the filesystem refuses, preserve
        // the old ready file rather than deleting it before a replacement has succeeded.
        if (!partial.renameTo(ready)) throw UpdateException("无法保存更新包。")
        ready
    }

    fun release(partial: File) = synchronized(lock) {
        if (active.remove(partial.absolutePath)) partial.delete()
        Unit
    }

    private fun cleanupLocked(directory: File): Int {
        var removed = 0
        directory.listFiles()?.forEach { file ->
            if (file.name.matches(partialName) && file.absolutePath !in active && file.isFile && file.delete()) removed++
        }
        return removed
    }
}
