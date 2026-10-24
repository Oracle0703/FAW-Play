package com.shilapi.xcertplay

import java.util.ArrayDeque

/** One on-screen debug log line; [id] is unique and stable for list keys. */
data class ScreenLogLine(val id: Long, val timestampMillis: Long, val text: String)

internal class ScreenLogBuffer(private val maxLines: Int = 100) {
    private val lines = ArrayDeque<ScreenLogLine>()
    private var nextId = 0L

    val firstTimestampMillis: Long?
        get() = lines.firstOrNull()?.timestampMillis

    val size: Int
        get() = lines.size

    fun add(timestampMillis: Long, text: String) {
        text.lineSequence().forEach { line ->
            lines.addLast(ScreenLogLine(nextId++, timestampMillis, line))
            if (lines.size > maxLines) lines.removeFirst()
        }
    }

    fun expireBefore(cutoffMillis: Long) {
        while (lines.firstOrNull()?.timestampMillis?.let { it <= cutoffMillis } == true) {
            lines.removeFirst()
        }
    }

    fun clear() = lines.clear()

    fun renderedText(): String = lines.joinToString("\n") { it.text }

    /** Immutable copy for the UI. */
    fun snapshot(): List<ScreenLogLine> = lines.toList()
}
