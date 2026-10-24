package com.shilapi.xcertplay.media

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.shared.BuildConfig
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.util.ArrayDeque

/** Opt-in compressed screen evidence. All file IO, Annex B conversion and frame logging use one writer. */
internal class VideoFrameTrace private constructor(
    private val directory: () -> File?,
    private val enabled: Boolean,
    private val limits: Limits,
    private val report: (String) -> Unit,
    private val nanoTime: () -> Long,
) : AutoCloseable {
    constructor(context: Context?, enabled: Boolean = BuildConfig.AUTUNN_VIDEO_TRACE) : this(
        { context?.applicationContext?.getExternalFilesDir(null)?.let { File(it, "video-trace") } },
        enabled && context != null, Limits(), { Log.i(TAG, it) }, System::nanoTime,
    )

    internal constructor(
        directory: File,
        limits: Limits = Limits(),
        nanoTime: () -> Long = { 0L },
        report: (String) -> Unit = {},
    ) : this({ directory }, true, limits, report, nanoTime)

    internal data class Limits(
        val windowNs: Long = 12_000_000_000L,
        val frames: Int = 360,
        val bytes: Long = 32L * 1024 * 1024,
        val sessionBytes: Long = 96L * 1024 * 1024,
        val captures: Int = 3,
        val preRollFrames: Int = 90,
        val preRollBytes: Int = 2 * 1024 * 1024,
        val queuedBytes: Int = 4 * 1024 * 1024,
        val queuedTasks: Int = 256,
    )

    private data class Config(val codec: VideoCodec, val csd: List<ByteArray>)
    private class Capture(val config: Config, val sessionId: Long, val touchNs: Long, val ordinal: Int) {
        val id = "cp-$sessionId-$touchNs-$ordinal"
        var admittedFrames = 0
        var admittedBytes = 0L
        var video: BufferedOutputStream? = null
        var timeline: BufferedWriter? = null
        var offset = 0L
        var writtenFrames = 0
        var ended = false
        val ptsToAu = HashMap<Long, Int>()
    }
    private class Frame(val bytes: ByteArray, val arrivalNs: Long, val senderNs: Long, val ptsUs: Long) {
        var submitNs = 0L
        var outputNs = 0L
        var presentNs = 0L
    }
    private sealed interface Task {
        val bytes: Int get() = 0
        data class Reset(val config: Config) : Task {
            override val bytes get() = config.csd.sumOf { it.size }
        }
        data class Start(val capture: Capture) : Task
        data class Picture(val frame: Frame, val capture: Capture?, val gap: Boolean) : Task {
            override val bytes get() = frame.bytes.size
        }
        data class Stage(val name: String, val ptsUs: Long, val timeNs: Long) : Task
        data class End(val capture: Capture, val reason: String) : Task
    }

    private val lock = Object()
    private val tasks = ArrayDeque<Task>()
    private var queuedBytes = 0
    private var worker: Thread? = null
    private var closed = false
    private var config: Config? = null
    private var active: Capture? = null
    private var sessionId: Long? = null
    private var captures = 0
    private var sessionBytes = 0L
    private var gap = false

    /** CSD must be the Annex B parameter sets supplied to MediaCodec, not avcC/hvcC. */
    fun onConfig(codec: VideoCodec, csd: List<ByteArray>) {
        if (!enabled) return
        synchronized(lock) {
            if (closed) return
            finishLocked("config-change")
            if (csd.isEmpty() || csd.any { it.isEmpty() } || csd.sumOf { it.size.toLong() } > 128 * 1024) {
                config = null
                report("drop reason=invalid-csd")
                return
            }
            val next = Config(codec, csd.map { it.copyOf() })
            config = if (offerLocked(Task.Reset(next))) next else null
        }
    }

    /** A fresh logical session ID resets the three-clip/96 MiB budget. Repeated touches start new clips. */
    fun startCapture(sessionId: Long, touchSentNs: Long): Boolean {
        if (!enabled) return false
        synchronized(lock) {
            if (closed) return false
            finishLocked("next-touch")
            if (this.sessionId != sessionId) {
                this.sessionId = sessionId
                captures = 0
                sessionBytes = 0
            }
            val current = config ?: run { report("drop reason=no-csd"); return false }
            // Reserve the maximum pre-roll so subsequent admissions cannot exceed the disk/frame caps.
            val reserve = current.csd.sumOf { it.size.toLong() } + limits.preRollBytes
            if (captures >= limits.captures || sessionBytes + reserve > limits.sessionBytes || reserve >= limits.bytes) {
                report("limit session=$sessionId reason=capture-budget")
                return false
            }
            val capture = Capture(current, sessionId, touchSentNs, captures + 1).apply {
                admittedFrames = limits.preRollFrames
                admittedBytes = reserve
            }
            if (!offerLocked(Task.Start(capture))) return false
            captures++
            sessionBytes += reserve
            active = capture
            return true
        }
    }

    /** The received AU may be four-byte length-prefixed or Annex B; each saved AU has an offset/length row. */
    fun onFrame(nalus: ByteArray, arrivalNs: Long, senderNs: Long, ptsUs: Long) {
        if (!enabled) return
        synchronized(lock) {
            if (closed || config == null) return
            var capture = active
            if (capture != null) {
                val reason = when {
                    arrivalNs - capture.touchNs >= limits.windowNs -> "duration"
                    capture.admittedFrames >= limits.frames -> "frames"
                    capture.admittedBytes + nalus.size > limits.bytes -> "bytes"
                    sessionBytes + nalus.size > limits.sessionBytes -> "session-bytes"
                    else -> null
                }
                if (reason != null) { finishLocked(reason); capture = null }
            }
            // Never wait for the writer or disk. Check capacity before copying the network-owned array.
            if (tasks.size >= limits.queuedTasks || nalus.size > limits.queuedBytes - queuedBytes) {
                gap = true
                report("drop ptsUs=$ptsUs reason=writer-capacity")
                finishLocked("writer-capacity")
                return
            }
            val frame = Frame(nalus.copyOf(), arrivalNs, senderNs, ptsUs)
            offerLocked(Task.Picture(frame, capture, gap))
            gap = false
            if (capture != null) {
                capture.admittedFrames++
                capture.admittedBytes += nalus.size
                sessionBytes += nalus.size
            }
        }
    }

    fun onInput(ptsUs: Long, timeNs: Long) = stage("submit", ptsUs, timeNs)
    fun onOutput(ptsUs: Long, timeNs: Long) = stage("output", ptsUs, timeNs)
    fun onPresent(ptsUs: Long, timeNs: Long) = stage("present", ptsUs, timeNs)

    private fun stage(name: String, ptsUs: Long, timeNs: Long) {
        if (!enabled) return
        synchronized(lock) { if (!closed) offerLocked(Task.Stage(name, ptsUs, timeNs)) }
    }

    private fun offerLocked(task: Task): Boolean {
        if (tasks.size >= limits.queuedTasks || task.bytes > limits.queuedBytes - queuedBytes) {
            report("drop event=${task.javaClass.simpleName} reason=writer-capacity")
            return false
        }
        tasks.addLast(task)
        queuedBytes += task.bytes
        if (worker == null) worker = Thread(::writeLoop, "carplay-video-trace").apply { isDaemon = true; start() }
        lock.notifyAll()
        return true
    }

    private fun finishLocked(reason: String) {
        val capture = active ?: return
        active = null
        // One reserved control slot guarantees an admitted clip can always end, even after a frame drop.
        tasks.addLast(Task.End(capture, reason))
        lock.notifyAll()
    }

    override fun close() {
        if (!enabled) return
        synchronized(lock) {
            if (closed) return
            finishLocked("close")
            closed = true
            lock.notifyAll()
        }
    }

    /** Tests and export tooling may wait off the playback threads; close itself never waits. */
    internal fun awaitClosed(timeoutMs: Long): Boolean {
        worker?.join(timeoutMs)
        return worker?.isAlive != true
    }

    private fun writeLoop() {
        val roll = ArrayDeque<Frame>()
        var rollBytes = 0
        var rollConfig: Config? = null
        val files = mutableListOf<Capture>()
        try {
            while (true) {
                val task = synchronized(lock) {
                    while (tasks.isEmpty() && !closed) {
                        val remaining = active?.let { limits.windowNs - (nanoTime() - it.touchNs) }
                        if (remaining != null && remaining <= 0) finishLocked("duration")
                        else lock.wait(remaining?.let { (it / 1_000_000).coerceIn(1, 250) } ?: 0L)
                    }
                    if (tasks.isEmpty()) null else tasks.removeFirst()
                } ?: break
                try {
                    when (task) {
                        is Task.Reset -> { roll.clear(); rollBytes = 0; rollConfig = task.config }
                        is Task.Start -> {
                            val capture = task.capture
                            if (files.firstOrNull()?.sessionId != capture.sessionId) {
                                files.forEach(::closeFiles); files.clear()
                            }
                            files.add(capture)
                            val root = directory() ?: error("external-directory-unavailable")
                            check(root.isDirectory || root.mkdirs()) { "external-directory-unavailable" }
                            val extension = if (capture.config.codec == VideoCodec.H265) "h265" else "h264"
                            capture.video = File(root, "${capture.id}.$extension").outputStream().buffered()
                            capture.timeline = File(root, "${capture.id}.tsv").bufferedWriter().apply {
                                appendLine("# session=${capture.sessionId} touchSentNs=${capture.touchNs} codec=${capture.config.codec}")
                                appendLine("event\tau\tptsUs\tbodyArrivalNs\tsenderNs\tsubmitNs\toutputNs\tpresentNs\toffset\tlength\tkey\tinfo")
                            }
                            capture.config.csd.forEach { capture.video!!.write(it); capture.offset += it.size }
                            if (rollConfig === capture.config && roll.isNotEmpty()) roll.forEach { writeFrame(capture, it, true) }
                            else capture.timeline!!.appendLine("note\t-1\t0\t0\t0\t0\t0\t0\t0\t0\t0\tno-decodable-preroll")
                            report("start capture=${capture.id} touchSentNs=${capture.touchNs} preroll=${capture.writtenFrames}")
                        }
                        is Task.Picture -> {
                            val frame = task.frame
                            val annexB = MediaCodecSupport.toAnnexB(frame.bytes)
                            if (task.gap || annexB.isEmpty()) { roll.clear(); rollBytes = 0 }
                            if (annexB.isEmpty()) {
                                task.capture?.let { endFile(it, "invalid-au") }
                                report("drop ptsUs=${frame.ptsUs} reason=invalid-au")
                            } else {
                                val saved = Frame(annexB, frame.arrivalNs, frame.senderNs, frame.ptsUs)
                                task.capture?.let { writeFrame(it, saved, false) }
                                if (rollConfig?.let { MediaCodecSupport.isRandomAccess(annexB, it.codec) } == true) {
                                    roll.clear(); rollBytes = 0
                                    if (annexB.size <= limits.preRollBytes) { roll.addLast(saved); rollBytes = annexB.size }
                                } else if (roll.isNotEmpty()) {
                                    if (roll.size >= limits.preRollFrames || rollBytes + annexB.size > limits.preRollBytes) {
                                        roll.clear(); rollBytes = 0
                                    } else { roll.addLast(saved); rollBytes += annexB.size }
                                }
                            }
                        }
                        is Task.Stage -> {
                            roll.firstOrNull { it.ptsUs == task.ptsUs }?.let { frame ->
                                when (task.name) {
                                    "submit" -> frame.submitNs = task.timeNs
                                    "output" -> frame.outputNs = task.timeNs
                                    "present" -> frame.presentNs = task.timeNs
                                }
                            }
                            files.forEach { capture ->
                                val au = capture.ptsToAu[task.ptsUs] ?: return@forEach
                                val submit = if (task.name == "submit") task.timeNs else 0L
                                val output = if (task.name == "output") task.timeNs else 0L
                                val present = if (task.name == "present") task.timeNs else 0L
                                capture.timeline?.appendLine("${task.name}\t$au\t${task.ptsUs}\t0\t0\t$submit\t$output\t$present\t0\t0\t0\t")
                                if (capture.ended) capture.timeline?.flush()
                                report("${task.name} capture=${capture.id} au=$au ptsUs=${task.ptsUs} ${task.name}Ns=${task.timeNs}")
                            }
                        }
                        is Task.End -> endFile(task.capture, task.reason)
                    }
                } catch (_: Exception) {
                    report("drop event=${task.javaClass.simpleName} reason=writer-io")
                    files.forEach { it.ended = true; closeFiles(it) }
                    synchronized(lock) {
                        closed = true
                        active = null
                        config = null
                        tasks.clear()
                        queuedBytes = task.bytes
                    }
                    break
                } finally {
                    synchronized(lock) { queuedBytes -= task.bytes }
                }
            }
        } finally { files.forEach(::closeFiles) }
    }

    private fun writeFrame(capture: Capture, frame: Frame, preRoll: Boolean) {
        if (capture.ended || capture.video == null) return
        val au = capture.writtenFrames++
        val offset = capture.offset
        capture.video!!.write(frame.bytes)
        capture.offset += frame.bytes.size
        capture.ptsToAu[frame.ptsUs] = au
        val key = if (MediaCodecSupport.isRandomAccess(frame.bytes, capture.config.codec)) 1 else 0
        capture.timeline!!.appendLine("frame\t$au\t${frame.ptsUs}\t${frame.arrivalNs}\t${frame.senderNs}\t${frame.submitNs}\t${frame.outputNs}\t${frame.presentNs}\t$offset\t${frame.bytes.size}\t$key\t${if (preRoll) "preroll" else "live"}")
        report("frame capture=${capture.id} au=$au ptsUs=${frame.ptsUs} bodyArrivalNs=${frame.arrivalNs} senderNs=${frame.senderNs} offset=$offset length=${frame.bytes.size} key=$key")
    }

    private fun endFile(capture: Capture, reason: String) {
        if (capture.ended) return
        capture.ended = true
        capture.video?.close(); capture.video = null
        capture.timeline?.appendLine("end\t-1\t0\t0\t0\t0\t0\t0\t${capture.offset}\t0\t0\t$reason")
        capture.timeline?.flush()
        report("end capture=${capture.id} frames=${capture.writtenFrames} bytes=${capture.offset} reason=$reason")
    }

    private fun closeFiles(capture: Capture) {
        runCatching { capture.video?.close() }; capture.video = null
        runCatching { capture.timeline?.close() }; capture.timeline = null
    }

    companion object { private const val TAG = "VideoFrameTrace" }
}
