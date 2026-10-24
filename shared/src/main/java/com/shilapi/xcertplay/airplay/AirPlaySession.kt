package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class AirPlayDeviceInfo(
    val name: String,
    val deviceId: String,
    val wifiMac: String,
    val model: String,
)

/** Session lifecycle and command callbacks for the driver/UI layer. */
interface AirPlaySessionListener {
    /** Admission for a new session, checked before allocating SETUP media or accepting RECORD. */
    fun authorizeSession(session: AirPlaySession): Boolean = true
    fun onTcpAccepted(event: AirPlayTcpAccepted) {}
    fun onSessionActive(session: AirPlaySession) {}
    /** The primary video stream was accepted; no display Surface or rendered frame is required. */
    fun onMainScreenReady(session: AirPlaySession) {}
    /** First decrypted picture from the current primary producer, including background reception. */
    fun onMainScreenVideoReceived(session: AirPlaySession) {}
    /** Input is invalid and the peer is gone; invoked before potentially slow media cleanup. */
    fun onSessionDisconnected(session: AirPlaySession) {}
    /** Session resources have been released. */
    fun onSessionEnded(session: AirPlaySession) {}
    /** Legacy primary-screen callback. Alternate-screen frames must not complete startup. */
    fun onVideoFrameRendered(session: AirPlaySession) {}
    fun onVideoFrameRendered(session: AirPlaySession, screenType: Int) {
        if (screenType == 110) onVideoFrameRendered(session)
    }
    /** Authenticated runtime iAP2 is independent of video readiness. */
    fun onWirelessRuntimeReady(session: AirPlaySession) {}
    fun onWirelessRuntimeUnavailable(session: AirPlaySession) {}
    /** An authenticated current navigation source delivered data; not a claim of visible guidance. */
    fun onNavigationDataReceived(session: AirPlaySession) {}
    /** Receiver progress, never confirmation of the OEM display's actual pixels. */
    fun onClusterContentProgress(session: AirPlaySession, progress: ClusterContentProgress) {}
    fun onTransportError(message: String) {}
    fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {}
    fun onHostUiRequested(session: AirPlaySession) {}
    fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {}
    /** Video in car: a playback message on a remote control session (X-Apple-StreamID). */
    fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) {}
    /** Video in car: requestUI "videoplayback:", the iPhone asks the car to show its video player. */
    fun onVideoPlaybackUiRequested(session: AirPlaySession) {}
    fun onDebugLog(message: String) {}
}

/** Stream transport seam; media decode/render is supplied by a later layer. */
interface AirPlayMediaHandler {
    fun setWirelessDiagnosticSource(source: () -> String) {}
    fun onSessionActive(session: AirPlaySession) {}
    fun onPlaybackContext(session: AirPlaySession, playback: CarPlayNowPlaying, phone: Boolean?, speech: Boolean?) {}
    fun onSiriRequested(session: AirPlaySession) {}
    fun onResourceModes(session: AirPlaySession, state: AirPlayResourceModes.State) {}
    fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? = null
    fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? = null
    fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? = null
    fun onFeedback(session: AirPlaySession): Map<String, Any?>? = null
    fun onTeardown(session: AirPlaySession, type: Int) {}
    /** null ends the whole session, including any audio tail whose receiver has already ended. */
    fun onTeardown(session: AirPlaySession, types: List<Int>?) {
        (types ?: session.activeStreams.toList()).forEach { onTeardown(session, it) }
    }
    fun onSessionClosed(session: AirPlaySession) {}
    fun setIapTunnelHandler(handler: ((AirPlaySession, BlockingDuplexByteStream) -> Boolean)?) {}
    fun onSetupResponseSent(session: AirPlaySession) {}
}

/**
 * One CarPlay AirPlay control connection.
 *
 * It owns the RTSP framing, pairing/auth/info routing, the encrypted event channel used for HID
 * input, stream SETUP/TEARDOWN routing, the NTP timing exchange, and the keep-alive socket.
 */
class AirPlaySession(
    private val socket: Socket,
    private val config: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    private val mfi: MfiAuthenticator?,
    private val listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    private val partialMessageTimeoutMillis: Long = IncompleteMessageDeadline.DEFAULT_TIMEOUT_MILLIS,
) : Closeable {
    internal val pairSetup = PairSetup(identity, pairings)
    internal val pairVerify = PairVerify(identity, pairings)
    internal var cipher: ControlCipher? = null
    internal var encBuf = ByteArray(0)
    internal var deviceBtMac = ""
    internal val activeStreams = linkedSetOf<Int>()
    private val nextDataStreamId = AtomicLong(3L) // iAP=1 and video settings=2 remain reserved.
    private val dataSeedLock = Any()
    private val dataSeeds = mutableSetOf<String>()
    private val ultraDataSeeds = mutableSetOf<String>()
    @Volatile private var negotiatedFeatures = emptySet<String>()
    private val ultraStreams = AirPlayUltraStreams(
        config, { sharedSecret }, { localAddress }, { remoteAddress }, ::allocateDataStreamId,
        { debugLog(it, false) },
        reserveSeed = ::reserveUltraDataSeed,
    )

    internal fun allocateDataStreamId(): Long = nextDataStreamId.getAndIncrement()

    private fun reserveUltraDataSeed(seed: String): Boolean = synchronized(dataSeedLock) {
        if (!dataSeeds.add(seed)) false else { ultraDataSeeds.add(seed); true }
    }

    /** Counts the iPhone's cluster stream setups; 0 while no cluster stream is up. */
    @Volatile var clusterStream = 0
        private set
    private var clusterStreamSetups = 0
    @Volatile private var mainScreenToken: Any? = null
    @Volatile private var renderedMainScreenToken: Any? = null
    @Volatile private var mainVideoSource: Any? = null
    @Volatile private var receivedMainVideoSource: Any? = null
    @Volatile private var retiringMainVideoSource: Any? = null
    @Volatile private var lastMediaProgressNanos = 0L

    val diagnosticId: Long = connectionIds.incrementAndGet()
    private val controlTimeline = TransportTimeline { debugLog("session=$diagnosticId control $it") }
    private val eventTimeline = TransportTimeline { debugLog("session=$diagnosticId event $it") }
    private val commandTracker = AirPlayCommandTracker { debugLog("session=$diagnosticId $it") }
    private val closed = AtomicBoolean(false)
    private val notified = AtomicBoolean(false)
    private val cleanupComplete = CountDownLatch(1)
    @Volatile private var eventServer: ServerSocket? = null
    @Volatile private var eventSocket: Socket? = null
    @Volatile private var eventCipher: ControlCipher? = null
    private var eventCseq = 0
    private var pendingNightMode: Boolean? = null
    private val firstTouchSendLogged = AtomicBoolean(false)
    private val touchSendFailureLogged = AtomicBoolean(false)
    private val ntp = NtpClock()
    private var keepAliveSocket: DatagramSocket? = null
    private var keepAliveThread: Thread? = null
    private val eventWriteLock = Any()
    // Serializes event replacement and ready publication. Never hold the write lock while
    // entering VideoPlaybackAvailability: its sender acquires eventWriteLock in the reverse path.
    private val eventLifecycleLock = Any()
    private val viewAreaControl = AirPlayViewAreaControl(config, ::sendCommand).apply {
        preferredAlternate = { synchronized(clusterContentLock) { requestedClusterUrl } }
        selectAlternate = ::applyDisplayClusterSelection
        onDiagnostic = { debugLog(it) }
    }
    val resourceModes = AirPlayResourceModes(::sendCommand)
    private val eventThreads = CopyOnWriteArrayList<Thread>()

    val host: String = socket.inetAddress?.hostAddress ?: ""
    val localAddress: InetAddress? = socket.localAddress
    private val peerAddress: InetAddress? = socket.inetAddress
    internal val remoteAddress: InetAddress?
        get() = (socket.remoteSocketAddress as? InetSocketAddress)?.address
    val controllerId: String? get() = pairVerify.verifiedControllerId
    val sharedSecret: ByteArray? get() = pairVerify.shared?.copyOf()
    val videoInCar: Boolean get() = config.videoInCar
    internal val fastPeerDetection: Boolean get() = config.fastPeerDetection
    fun isClosed(): Boolean = closed.get()
    private val videoPlaybackAvailability = VideoPlaybackAvailability { allowed ->
        sendCommand(
            linkedMapOf(
                "type" to "setVideoPlaybackAllowed",
                "params" to linkedMapOf("videoPlaybackAllowed" to allowed),
            ),
        )
    }

    fun syncedNtp(): BigInteger = ntp.syncedNtp()

    internal fun logDebug(message: String) = debugLog(message)

    internal fun videoFrameRendered(screenType: Int = STREAM_TYPE_MAIN_SCREEN, source: Any? = null) {
        if (closed.get()) return
        if (screenType == STREAM_TYPE_MAIN_SCREEN) renderedMainScreenToken = mainScreenToken
        else if (screenType == STREAM_TYPE_ALT_SCREEN && source != null) {
            clusterProgress.rendered(source)?.let { publishClusterProgress(it); cancelClusterProgressCheck(it) }
        }
        listener.onVideoFrameRendered(this, screenType)
    }

    internal fun videoSourceStarted(screenType: Int, source: Any) {
        if (screenType == STREAM_TYPE_ALT_SCREEN && !closed.get()) clusterVideoSource = source
        else if (screenType == STREAM_TYPE_MAIN_SCREEN && !closed.get()) {
            receivedMainVideoSource = null
            retiringMainVideoSource = null
            mainVideoSource = source
        }
    }

    /** Capture the producer before requesting HOME; EOF can precede modesChanged/TEARDOWN. */
    internal fun expectMainScreenEnd() {
        if (!closed.get()) retiringMainVideoSource = mainVideoSource
    }

    internal fun mainScreenEndExpected(source: Any): Boolean = !closed.get() && mainVideoSource === source &&
        (retiringMainVideoSource === source || resourceModes.state.screen == AirPlayResourceModes.Entity.VEHICLE)

    /** The engine holds ownership of this producer; a late EOF cannot revoke its replacement. */
    internal fun mainScreenSourceEnded(source: Any) {
        if (mainVideoSource !== source) return
        mainScreenToken = null
        renderedMainScreenToken = null
        mainVideoSource = null
        receivedMainVideoSource = null
        retiringMainVideoSource = null
    }

    /** Called only for a decrypted picture access unit from the currently owned producer. */
    internal fun videoFrameReceived(screenType: Int, source: Any) {
        if (closed.get()) return
        if (screenType == STREAM_TYPE_MAIN_SCREEN) {
            if (mainVideoSource !== source || receivedMainVideoSource === source) return
            receivedMainVideoSource = source
            listener.onMainScreenVideoReceived(this)
            return
        }
        if (screenType != STREAM_TYPE_ALT_SCREEN) return
        clusterProgress.received(source)?.let { publishClusterProgress(it); cancelClusterProgressCheck(it) }
    }

    internal fun mediaProgress() {
        if (!closed.get()) lastMediaProgressNanos = System.nanoTime()
    }

    /** Actual decrypted media reception, independent of whether a display is in the foreground. */
    fun hasRecentMediaProgress(maxAgeMillis: Long): Boolean {
        val latest = lastMediaProgressNanos
        val age = System.nanoTime() - latest
        return !closed.get() && latest != 0L && maxAgeMillis > 0L && age >= 0L &&
            age <= TimeUnit.MILLISECONDS.toNanos(maxAgeMillis)
    }

    /** A frame belongs to the currently accepted primary stream, never a previous SETUP. */
    fun hasRenderedMainScreen(): Boolean {
        val token = mainScreenSessionToken() ?: return false
        return renderedMainScreenToken === token
    }

    fun hasReceivedMainScreenVideo(): Boolean = !closed.get() && mainScreenToken != null && mainVideoSource != null &&
        mainVideoSource === receivedMainVideoSource

    internal fun logTrace(message: String) = trace(message)

    fun start() {
        Thread(::runControl, "airplay-control").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        finishClose()
    }

    private fun finishClose() {
        controlTimeline.event("close initiator=session")
        eventTimeline.event("close initiator=session")
        mainScreenToken = null
        renderedMainScreenToken = null
        mainVideoSource = null
        receivedMainVideoSource = null
        retiringMainVideoSource = null
        clearClusterContent()
        ultraStreams.close()
        safeClose(socket)
        safeClose(eventServer)
        safeClose(eventSocket)
        // Close the socket before taking a resource-state lock held by a blocked writer.
        resourceModes.close()
        commandTracker.close()
        if (clusterProgressWorker.isInitialized()) clusterProgressWorker.value.shutdownNow()
        try {
            try { listener.onSessionDisconnected(this) }
            catch (error: Exception) { Log.w(TAG, "airplay disconnect notification failed", error) }
            try {
                media.onSessionClosed(this)
            } catch (error: Exception) {
                Log.w(TAG, "airplay media stream teardown failed", error)
            } finally {
                try { teardown() }
                catch (error: Exception) { Log.w(TAG, "airplay transport teardown failed", error) }
            }
            if (notified.compareAndSet(false, true)) {
                try { listener.onSessionEnded(this) }
                catch (error: Exception) { Log.w(TAG, "airplay end notification failed", error) }
            }
        } finally {
            cleanupComplete.countDown()
        }
    }

    /** Transport teardown workers may wait; the UI uses the early notification instead. */
    internal fun awaitClosed(timeoutMillis: Long): Boolean =
        cleanupComplete.await(timeoutMillis, TimeUnit.MILLISECONDS)

    /** Stable identity of the usable primary screen; replaced on SETUP, absent after teardown/close. */
    internal fun mainScreenSessionToken(): Any? = if (closed.get()) null else mainScreenToken

    /**
     * Asks the iPhone to draw CarPlay's cluster UI on the alt screen (showUI with the display's URL,
     * then a keyframe) or to stop drawing it (stopUI). The stream stays up either way; CarKit handles
     * both as car-initiated commands for a screen UUID.
     */
    fun setClusterUiShown(shown: Boolean): Boolean = setClusterUiShown(shown, expectedStream = null)

    /** A queued controller request may target only the stream admitted with that request. */
    internal fun setClusterUiShown(shown: Boolean, expectedStream: Int?): Boolean {
        synchronized(clusterCommandLock) {
            if (closed.get() || config.cluster == null) return false
            val content = synchronized(clusterContentLock) {
                if (expectedStream != null && clusterStream != expectedStream) return false
                requestedClusterShown = shown
                clusterContent?.let { current ->
                    current.copy(shown = shown, visibilityDelivered = false, token = Any())
                        .also { clusterContent = it }
                }
            } ?: return false
            if (clusterStream <= 0) return false
            if (!shown) {
                clusterProgress.request(content.stream, false)
                cancelClusterProgressCheck()
                val sent = sendClusterCommand(content, mapOf("type" to "stopUI",
                    "params" to mapOf("uuid" to AirPlayInfoPlist.ALT_UUID)))
                return synchronized(clusterContentLock) {
                    val current = clusterContent ?: return false
                    if (closed.get() || current.stream != content.stream) {
                        if (sent) clusterContent = current.copy(displayed = null, shown = false,
                            visibilityDelivered = false, token = Any())
                        return false
                    }
                    if (sent) {
                        clusterContent = current.copy(shown = false, visibilityDelivered = true)
                        clusterProgress.snapshot()?.let { clusterProgress.written(it.stream, it.revision, System.nanoTime()) }
                    }
                    clusterProgress.snapshot()?.let(::publishClusterProgress)
                    debugLog("Cluster content stream=${content.stream} requested=hidden written=$sent effect=unmeasured")
                    sent
                }
            }
            return showClusterUrl(content, content.requested ?: return false)
        }
    }

    /** SETUP admission can retain vehicle intent without writing to the previous stream. */
    internal fun retainClusterUiShown(shown: Boolean) = synchronized(clusterContentLock) {
        requestedClusterShown = shown
    }

    private data class ClusterContent(val stream: Int, val requested: String?, val displayed: String?,
        val shown: Boolean = true, val visibilityDelivered: Boolean = false,
        val setupAnswered: Boolean = false, val token: Any = Any())
    private val clusterContentLock = Any()
    private val clusterCommandLock = Any()
    @Volatile private var clusterContent: ClusterContent? = null
    private var requestedClusterUrl = config.cluster?.initialUrl // guarded by clusterContentLock
    private var requestedClusterShown = true // guarded by clusterContentLock
    @Volatile private var clusterVideoSource: Any? = null
    private val clusterProgress = ClusterContentMonitor()
    private var clusterProgressCheck: ScheduledFuture<*>? = null // guarded by clusterContentLock
    private var clusterProgressCheckOwner: Pair<Int, Long>? = null // stream and request revision
    private val clusterProgressWorker = lazy {
        ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "airplay-cluster-content").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true; setExecuteExistingDelayedTasksAfterShutdownPolicy(false) }
    }

    fun clusterContentProgress(): ClusterContentProgress? = if (closed.get()) null else clusterProgress.snapshot()

    private fun publishClusterProgress(progress: ClusterContentProgress) {
        if (closed.get()) return
        debugLog("Cluster progress stream=${progress.stream} revision=${progress.revision} shown=${progress.shown} " +
            "written=${progress.requestWritten} video=${progress.validVideoReceived} rendered=${progress.frameRendered} " +
            "attempts=${progress.attempts} failure=${progress.failure ?: "none"} effect=unmeasured")
        runCatching { listener.onClusterContentProgress(this, progress) }
            .onFailure { Log.w(TAG, "cluster progress listener failed", it) }
    }

    private fun cancelClusterProgressCheck(expected: ClusterContentProgress? = null) = synchronized(clusterContentLock) {
        if (expected != null && clusterProgressCheckOwner != (expected.stream to expected.revision)) return@synchronized
        clusterProgressCheck?.cancel(false)
        clusterProgressCheck = null
        clusterProgressCheckOwner = null
    }

    private fun scheduleClusterProgressCheck(progress: ClusterContentProgress): Unit = synchronized(clusterContentLock) {
        val current = clusterProgress.snapshot() ?: return@synchronized
        if (current.stream != progress.stream || current.revision != progress.revision) return@synchronized
        clusterProgressCheck?.cancel(false)
        clusterProgressCheck = null
        clusterProgressCheckOwner = null
        if (closed.get() || !current.shown || !current.requestWritten || current.validVideoReceived || current.frameRendered || current.failure != null) return@synchronized
        clusterProgressCheckOwner = current.stream to current.revision
        clusterProgressCheck = clusterProgressWorker.value.schedule({
            synchronized(clusterCommandLock) check@{
                if (closed.get()) return@check
                val content = clusterContent ?: return@check
                if (!content.shown || content.stream != progress.stream || clusterStream != progress.stream) return@check
                when (clusterProgress.check(progress.stream, progress.revision, System.nanoTime())) {
                    ClusterContentMonitor.Action.RETRY -> {
                        // Only an entirely missing first access unit is retried. A static map
                        // that has supplied valid video is never judged by its frame rate.
                        showClusterUrl(content, content.requested ?: return@check, progress.revision)
                        return@check // A successful write schedules exactly one next check.
                    }
                    ClusterContentMonitor.Action.REPORT -> clusterProgress.snapshot()?.let(::publishClusterProgress)
                    ClusterContentMonitor.Action.NONE -> Unit
                }
                val latest = clusterProgress.snapshot() ?: return@check
                if (latest.stream == progress.stream && latest.revision == progress.revision && latest.failure == null)
                    scheduleClusterProgressCheck(latest)
            }
        }, ClusterContentMonitor.RETRY_WAIT_NS, TimeUnit.NANOSECONDS)
    }

    /** Last content whose commands were written for this stream; this is not proof of its pixels. */
    fun clusterUrl(): String? = clusterContent?.takeIf { !closed.get() && it.stream == clusterStream }?.displayed

    /** One immutable snapshot keeps content and generation checks consistent without locking keys. */
    internal fun clusterContentRoute(): Triple<Int, String?, Any>? {
        val content = clusterContent ?: return null
        if (closed.get() || content.stream != clusterStream) return null
        return Triple(content.stream, content.displayed.takeIf { content.shown && content.visibilityDelivered }, content.token)
    }

    /**
     * Switches the dashboard to another of the iPhone's `altScreenURLs` without reconnecting: `showUI`
     * with that URL and a keyframe (on a Tang this switches map, turn card and both at once). With [send]
     * false (the map is paused) the URL is only kept for the next `showUI`. False without a cluster stream.
     */
    fun setClusterUrl(url: String, send: Boolean): Boolean {
        return synchronized(clusterCommandLock) {
            val content = synchronized(clusterContentLock) {
                val current = clusterContent ?: return false
                if (closed.get() || current.stream != clusterStream || config.cluster == null) return false
                requestedClusterUrl = url
                current.copy(requested = url).also { clusterContent = it }
            }
            if (!send) true else showClusterUrl(content, url)
        }
    }

    /** Seeds a retained controller selection even when this phone has not set up its cluster yet. */
    internal fun restoreClusterUrl(url: String, send: Boolean): Boolean = synchronized(clusterCommandLock) {
        val content = synchronized(clusterContentLock) {
            if (closed.get() || config.cluster == null) return false
            requestedClusterUrl = url
            clusterContent?.copy(requested = url)?.also { clusterContent = it }
        }
        if (content == null || !send || (content.shown && content.visibilityDelivered && content.displayed == url)) true
        else showClusterUrl(content, url)
    }

    private fun showClusterUrl(content: ClusterContent, url: String, retryRevision: Long? = null): Boolean {
        val progress = if (retryRevision == null) clusterProgress.request(content.stream, true)
            else clusterProgress.snapshot()?.takeIf { it.stream == content.stream && it.revision == retryRevision && it.shown &&
                !it.validVideoReceived && !it.frameRendered && it.failure == null }
        if (progress == null) return false
        cancelClusterProgressCheck()
        synchronized(clusterContentLock) {
            val current = clusterContent ?: return false
            if (closed.get() || clusterStream != content.stream || current.stream != content.stream) return false
            requestedClusterShown = true
            // A request made while SETUP is being answered must still replace a retained hide.
            clusterContent = current.copy(shown = true, visibilityDelivered = false, token = Any())
        }
        val uuid = AirPlayInfoPlist.ALT_UUID
        val uiSent = sendClusterCommand(content, mapOf("type" to "showUI", "params" to mapOf("uuid" to uuid, "url" to url)))
        val sent = uiSent && clusterStream == content.stream && !closed.get() &&
            sendClusterCommand(content, mapOf("type" to "forceKeyFrame", "params" to mapOf("uuid" to uuid)))
        return synchronized(clusterContentLock) {
            val current = clusterContent ?: return false
            if (closed.get() || current.stream != content.stream || clusterStream != content.stream) {
                // A command already in flight can outlast re-SETUP. Its displayed result is unknown.
                if (uiSent) clusterContent = current.copy(displayed = null, visibilityDelivered = false, token = Any())
                return false
            }
            if (uiSent) clusterContent = current.copy(displayed = if (sent) url else null,
                shown = true, visibilityDelivered = sent, token = Any())
            if (sent) clusterProgress.written(progress.stream, progress.revision, System.nanoTime())?.let {
                publishClusterProgress(it)
                scheduleClusterProgressCheck(it)
            }
            debugLog("Cluster content stream=${content.stream} requested=shown showWritten=$uiSent " +
                "keyFrameWritten=$sent effect=unmeasured")
            sent
        }
    }

    /** All content entry points wait for this generation's SETUP response to reach the phone. */
    private fun sendClusterCommand(content: ClusterContent, command: Map<String, Any?>): Boolean =
        synchronized(eventWriteLock) {
            val current = clusterContent ?: return false
            if (current.stream != content.stream || clusterStream != content.stream || !current.setupAnswered) return false
            sendCommandLocked(command)
        }

    private fun beginClusterContent() {
        val initial = viewAreaControl.initialAlternateUrl()
        synchronized(clusterContentLock) {
            val stream = ++clusterStreamSetups
            // initialURL is an offered starting page, not evidence that this stream drew it.
            clusterContent = ClusterContent(stream, requestedClusterUrl ?: initial,
                displayed = null, shown = requestedClusterShown)
            clusterStream = stream
            clusterProgress.begin(stream, clusterVideoSource, requestedClusterShown)
            cancelClusterProgressCheck()
        }
        clusterProgress.snapshot()?.let(::publishClusterProgress)
    }

    private fun applyDisplayClusterSelection(url: String): Boolean = synchronized(clusterCommandLock) {
        val content = clusterContent ?: return false
        if (closed.get() || content.stream != clusterStream) return false
        // Context suggestions cannot undo the vehicle's paused instrument display.
        if (!restoreClusterUrl(url, send = false)) return false
        if (!content.shown) true else showClusterUrl(clusterContent ?: return false, url)
    }

    private fun clearClusterContent() = synchronized(clusterContentLock) {
        clusterStream = 0
        clusterContent = null
        clusterVideoSource = null
        clusterProgress.clear()
        cancelClusterProgressCheck()
    }

    // Explicitly request each stream's first page after SETUP was answered and the event channel
    // is usable. Retain a failed hide/show for the ready-channel retry without undoing a pause.
    private fun reapplyClusterContent() {
        synchronized(clusterCommandLock) {
            val content = clusterContent ?: return
            if (!content.setupAnswered) return
            val requested = content.requested ?: return
            if (content.shown && (!content.visibilityDelivered || requested != content.displayed)) {
                showClusterUrl(content, requested)
            } else if (!content.shown && !content.visibilityDelivered) setClusterUiShown(false)
        }
    }

    /**
     * Zooms CarPlay's dashboard map one step, as a car's own zoom controls do: the car command
     * changeMapZoomLevel for the cluster screen's UUID. Seen with Apple Maps on a Tang: zoomDirection 0
     * zooms in, 1 zooms out. Sent only while the cluster stream is up.
     */
    fun changeMapZoomLevel(zoomIn: Boolean): Boolean = clusterStream > 0 && sendCommand(
        linkedMapOf(
            "type" to "changeMapZoomLevel",
            "params" to linkedMapOf(
                "uuid" to AirPlayInfoPlist.ALT_UUID,
                "zoomDirection" to if (zoomIn) ZOOM_DIRECTION_IN else ZOOM_DIRECTION_OUT,
            ),
        ),
    )

    fun sendCommand(command: Map<String, Any?>): Boolean = synchronized(eventWriteLock) {
        sendCommandLocked(command)
    }

    /**
     * A message on a video in car remote control session, framed like the iPhone's own: POST /command
     * with X-Apple-StreamID and {params: {data: bplist(message)}}.
     */
    fun sendRemoteControlMessage(streamId: Long, message: Map<String, Any?>): Boolean = synchronized(eventWriteLock) {
        sendCommandLocked(
            linkedMapOf("params" to linkedMapOf("data" to BplistCodec.encode(message))),
            "X-Apple-StreamID: $streamId\r\n",
        )
    }

    /**
     * Video in car: retains whether video may play now and sends the latest value once SETUP has
     * enabled video and the event channel is ready. An iPhone without the feature gets no command.
     */
    internal fun setVideoPlaybackAllowed(allowed: Boolean): VideoPlaybackDelivery =
        videoPlaybackAvailability.setDesired(allowed)

    private fun sendCommandLocked(command: Map<String, Any?>, extraHeaders: String = ""): Boolean {
        if (closed.get()) return false
        val socket = eventSocket ?: return false
        val cipher = eventCipher ?: return false
        eventCseq++
        val body = BplistCodec.encode(command)
        val head = "POST /command RTSP/1.0\r\n" + extraHeaders +
            "Content-Type: $PLIST_CONTENT_TYPE\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "CSeq: $eventCseq\r\n\r\n"
        trace("airplay event tx method=POST route=command cseq=$eventCseq contentBytes=${body.size}")
        commandTracker.queued(eventCseq, command)
        return try {
            val bytes = cipher.encrypt(head.toByteArray(Charsets.US_ASCII) + body)
            val output = socket.getOutputStream()
            eventTimeline.beginWrite()
            output.write(bytes)
            output.flush()
            eventTimeline.written()
            commandTracker.written(eventCseq)
            true
        } catch (error: Exception) {
            commandTracker.failed(eventCseq)
            eventTimeline.failure("write", error.javaClass.simpleName)
            Log.w(TAG, "airplay event command failed type=${command["type"]}", error)
            // The owned reader performs session/media teardown after its read is unblocked.
            // Never run that teardown while holding eventWriteLock: availability senders
            // acquire this lock while holding their own state monitor.
            safeClose(socket)
            false
        }
    }

    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        val scaled = contacts.map {
            it.copy(x = it.x * config.main.widthPixels, y = it.y * config.main.heightPixels)
        }
        val report = AirPlayHid.touchReport(scaled)
        val sendStartNs = System.nanoTime()
        val sent = sendHidReport(AirPlayHid.TOUCH_HID_UID, report)
        if (sent) com.shilapi.xcertplay.media.TouchLatencyProbe.onTouchSent(sendStartNs, System.nanoTime() - sendStartNs)
        if (sent && firstTouchSendLogged.compareAndSet(false, true)) {
            val first = scaled.firstOrNull()
            Log.i(
                TAG,
                "airplay touch report sent contacts=${scaled.size} first=" +
                    "(${first?.x},${first?.y},down=${first?.down}) report=${report.toHexString()}",
            )
        } else if (!sent && touchSendFailureLogged.compareAndSet(false, true)) {
            Log.w(TAG, "airplay touch dropped: event channel is not ready")
        }
        return sent
    }

    fun sendKnob(state: AirPlayKnobState, momentary: Boolean = true) {
        sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(state))
        if (momentary) sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(AirPlayKnobState()))
    }

    fun sendKnobSelect(down: Boolean) =
        sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(AirPlayKnobState(select = down)))

    fun sendMedia(index: Int) {
        val pressed = sendHidReport(AirPlayHid.MEDIA_HID_UID, AirPlayHid.mediaReport(index))
        val released = sendHidReport(AirPlayHid.MEDIA_HID_UID, AirPlayHid.mediaReport(0))
        Log.i(TAG, "airplay media index=$index pressed=$pressed released=$released")
    }

    fun sendTelephony(index: Int) {
        sendHidReport(AirPlayHid.TELEPHONY_HID_UID, AirPlayHid.telephonyReport(index))
        sendHidReport(AirPlayHid.TELEPHONY_HID_UID, AirPlayHid.telephonyReport(0))
    }

    fun sendSiriButton(down: Boolean): Boolean =
        sendCommand(linkedMapOf("type" to "requestSiri", "params" to linkedMapOf("siriAction" to if (down) 2 else 3)))

    fun invokeSiri() {
        val pressed = sendSiriButton(true)
        val released = sendSiriButton(false)
        Log.i(TAG, "airplay Siri pressed=$pressed released=$released")
    }

    fun sendIapMessage(data: ByteArray, timeoutMillis: Long = 0L): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val command = linkedMapOf<String, Any?>(
            "type" to "iAPSendMessage",
            "params" to linkedMapOf("data" to data),
        )
        if (timeoutMillis == 0L) return sendCommand(command)

        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        while (true) {
            val sent = synchronized(eventWriteLock) {
                if (eventSocket == null || eventCipher == null) {
                    null
                } else {
                    sendCommandLocked(command)
                }
            }
            if (sent != null) return sent
            if (closed.get()) return false

            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) return false
            val sleepMillis = minOf(
                EVENT_READY_POLL_MILLIS,
                (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND,
            ).coerceAtLeast(1)
            try {
                Thread.sleep(sleepMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    fun setNightMode(night: Boolean): Boolean = synchronized(eventWriteLock) {
        pendingNightMode = night
        sendPendingNightModeLocked()
    }

    private fun sendPendingNightModeLocked(): Boolean {
        val night = pendingNightMode ?: return true
        val sent = sendCommandLocked(
            linkedMapOf("type" to "setNightMode", "params" to linkedMapOf("nightMode" to night)),
        )
        if (sent) pendingNightMode = null
        return sent
    }

    private fun sendHidReport(uid: Int, report: ByteArray): Boolean =
        sendCommand(
            linkedMapOf(
                "type" to "hidSendReport",
                "uuid" to uid.toString(16),
                "hidReport" to report,
            ),
        )

    private fun runControl() {
        com.shilapi.xcertplay.network.TcpLiveness.configure(socket, config.fastPeerDetection) {
            debugLog("session=$diagnosticId control $it")
        }
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        var accumulated = ByteArray(0)
        val buffer = ByteArray(READ_CHUNK_BYTES)
        val cipherTimes = MessageByteTimes()
        val messageTimes = MessageByteTimes()
        val frameDeadline = IncompleteMessageDeadline("control encrypted frame", partialMessageTimeoutMillis) { safeClose(socket) }
        val messageDeadline = IncompleteMessageDeadline("control RTSP message", partialMessageTimeoutMillis) { safeClose(socket) }
        var closeReason = "session closed"
        try {
            while (!closed.get()) {
                controlTimeline.beginRead()
                val count = input.read(buffer)
                val receivedAt = System.nanoTime()
                controlTimeline.read()
                frameDeadline.check()
                messageDeadline.check()
                if (count < 0) {
                    controlTimeline.failure("read", "EOF")
                    closeReason = "peer EOF"
                    break
                }
                if (count == 0) continue
                var plaintext = buffer.copyOf(count)
                val activeCipher = cipher
                if (activeCipher != null) {
                    encBuf += plaintext
                    cipherTimes.append(count, receivedAt)
                    val decrypted = try {
                        activeCipher.decrypt(encBuf)
                    } catch (error: Exception) {
                        controlTimeline.failure("decrypt", error.javaClass.simpleName)
                        closeReason = "control decrypt failed: ${error.message ?: error.javaClass.simpleName}"
                        Log.e(TAG, "airplay $closeReason encrypted=${encBuf.size}", error)
                        break
                    }
                    cipherTimes.transferAuthenticatedFrames(encBuf, encBuf.size - decrypted.rest.size, messageTimes)
                    encBuf = decrypted.rest
                    frameDeadline.update(cipherTimes.firstNanos)
                    plaintext = decrypted.data
                } else messageTimes.append(count, receivedAt)
                accumulated += plaintext
                val parsed = RtspMessage.parseMessages(accumulated)
                messageTimes.consume(accumulated.size - parsed.rest.size)
                accumulated = parsed.rest
                messageDeadline.update(messageTimes.firstNanos)
                if (parsed.messages.isNotEmpty()) viewAreaControl.responseStarted()
                for (request in parsed.messages) {
                    val cseq = request.headers["cseq"] ?: "-"
                    val path = request.path.lowercase()
                    val showInDebugOverlay =
                        !path.endsWith("/feedback") &&
                            !(request.method == "POST" && path.endsWith("/command"))
                    debugLog(
                        "airplay rx ${request.method} ${request.path} cseq=$cseq body=${request.body.size}",
                        showInDebugOverlay,
                    )
                    if (showInDebugOverlay) debugLog(
                        AirPlayControlDiagnostics.request(request.method, request.path, request.body.size),
                        false,
                    )
                    val response = try {
                        handle(request)
                    } catch (error: Exception) {
                        Log.e(
                            TAG,
                            "airplay handler failed ${request.method} ${request.path} cseq=$cseq",
                            error,
                        )
                        RtspMessage.Response(status = 500)
                    }
                    debugLog(
                        "airplay tx status=${response.status ?: 200} cseq=$cseq body=${response.body.size}",
                        showInDebugOverlay,
                    )
                    if (showInDebugOverlay) debugLog(
                        "airplay control response status=${response.status ?: 200} contentBytes=${response.body.size}",
                        false,
                    )
                    val wire = RtspMessage.buildResponse(request, response)
                    // Never format the payload before writing: /info can be tens of KB on a
                    // slow head unit. Byte counts retain diagnostics without per-byte allocations.
                    trace("airplay control tx status=${response.status ?: 200} cseq=$cseq " +
                        "contentBytes=${response.body.size} wireBytes=${wire.size}")
                    controlTimeline.beginWrite()
                    output.write(cipher?.encrypt(wire) ?: wire)
                    if (cipher == null && pairVerify.controlKeys != null) {
                        val keys = pairVerify.controlKeys!!
                        cipher = ControlCipher(keys.readKey, keys.writeKey)
                        debugLog("airplay control encryption enabled")
                    }
                }
                output.flush()
                controlTimeline.written()
                if (parsed.messages.isNotEmpty()) viewAreaControl.responseSent()
                notifySetupResponseSent()
            }
        } catch (error: Exception) {
            val failure = messageDeadline.failureOr(frameDeadline.failureOr(error))
            if (!closed.get()) controlTimeline.failure("io", failure.javaClass.simpleName)
            closeReason = "control I/O failed: ${failure.message ?: failure.javaClass.simpleName}"
            if (!closed.get()) Log.e(TAG, "airplay $closeReason", failure)
        } finally {
            frameDeadline.close()
            messageDeadline.close()
            debugLog("airplay control closing reason=$closeReason activeStreams=$activeStreams")
            close()
        }
    }

    private fun handle(request: RtspMessage.Request): RtspMessage.Response {
        if (request.method in setOf("SETUP", "RECORD") && !listener.authorizeSession(this)) {
            return RtspMessage.Response(status = 403)
        }
        when (request.method) {
            "SETUP" -> return handleSetup(request)
            "RECORD" -> {
                listener.onSessionActive(this)
                return RtspMessage.Response(status = 200)
            }
            "TEARDOWN" -> return handleTeardown(request)
        }

        val path = request.path.lowercase()
        return when {
            path.endsWith("/pair-setup") -> RtspMessage.Response(
                headers = mapOf("Content-Type" to PAIRING_CONTENT_TYPE),
                body = pairSetup.handle(request.body),
            )
            path.endsWith("/pair-verify") -> RtspMessage.Response(
                headers = mapOf("Content-Type" to PAIRING_CONTENT_TYPE),
                body = pairVerify.handle(request.body),
            )
            path.endsWith("/auth-setup") -> {
                val body = mfi?.let { MfiSapAuthSetup.handle(request.body, it) }
                if (body == null) RtspMessage.Response(status = 400)
                else RtspMessage.Response(headers = mapOf("Content-Type" to OCTET_CONTENT_TYPE), body = body)
            }
            path.endsWith("/info") -> {
                val requestInfo = if (request.body.isEmpty()) emptyMap() else
                    runCatching { asMap(BplistCodec.decode(request.body)) }.getOrNull() ?: emptyMap()
                val info = viewAreaControl.info(requestInfo)
                Log.i(
                    TAG,
                    "airplay /info features=${info["features"]} " +
                        "audioFormats=${(info["audioFormats"] as? List<*>)?.size ?: 0} " +
                        "audioLatencies=${(info["audioLatencies"] as? List<*>)?.size ?: 0}",
                )
                debugLog(
                    "airplay /info videoInCar=${config.videoInCar} " +
                        "videoPlaybackAllowed=${if (config.videoInCar) VideoInCar.allowed else "not-offered"}",
                )
                debugLog("airplay /info displays=${info["displays"]}")
                RtspMessage.Response(
                    headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                    body = BplistCodec.encode(info),
                )
            }
            request.method == "POST" && path.endsWith("/command") -> handleCommand(request)
            request.method == "POST" && path.endsWith("/feedback") -> {
                val body = media.onFeedback(this)
                if (body == null) {
                    RtspMessage.Response(status = 200)
                } else {
                    RtspMessage.Response(
                        headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                        body = BplistCodec.encode(body),
                    )
                }
            }
            else -> RtspMessage.Response(status = 200)
        }
    }

    private fun notifySetupResponseSent() {
        val stream = clusterStream
        ultraStreams.responseSent()
        try {
            media.onSetupResponseSent(this)
        } catch (error: Exception) {
            Log.w(TAG, "airplay SETUP response callback failed", error)
        }
        synchronized(clusterContentLock) {
            clusterContent?.takeIf { it.stream == stream }?.let { clusterContent = it.copy(setupAnswered = true) }
        }
        reapplyClusterContent()
    }

    private fun debugLog(message: String, uiVisible: Boolean = true) {
        Log.i(TAG, message)
        if (!uiVisible) return
        try {
            listener.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(TAG, "debug log callback failed", error)
        }
    }

    private fun trace(message: String) {
        try {
            listener.onDebugLog("TRACE $message")
        } catch (error: Exception) {
            Log.w(TAG, "trace log callback failed", error)
        }
    }

    private fun handleSetup(request: RtspMessage.Request): RtspMessage.Response {
        val dict = try {
            asMap(BplistCodec.decode(request.body)) ?: return RtspMessage.Response(status = 400)
        } catch (error: Exception) {
            Log.e(TAG, "airplay SETUP plist decode failed body=${request.body.size}", error)
            return RtspMessage.Response(status = 400)
        }
        debugLog("airplay SETUP keys=${dict.keys.sorted()}")
        val streams = dict["streams"] as? List<*>
        if (streams != null) {
            val responseStreams = handleStreams(streams)
            debugLog("airplay SETUP response streams=$responseStreams")
            val body = BplistCodec.encode(linkedMapOf("streams" to responseStreams))
            trace("airplay SETUP response contentBytes=${body.size}")
            return RtspMessage.Response(headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE), body = body)
        }

        val name = string(dict["name"])
        val deviceId = string(dict["deviceID"])
        val wifiMac = string(dict["macAddress"]).lowercase()
        val model = string(dict["model"])
        debugLog(AirPlayPeerDiagnostics.summary(model, string(dict["osVersion"]), string(dict["sourceVersion"])))
        if (deviceId.isNotEmpty()) deviceBtMac = deviceId
        if (name.isNotEmpty() || deviceId.isNotEmpty() || wifiMac.isNotEmpty()) {
            listener.onDeviceInfo(this, AirPlayDeviceInfo(name, deviceId, wifiMac, model))
        }

        val peerTimingPort = long(dict["timingPort"])?.toInt() ?: 0
        val response = linkedMapOf<String, Any?>(
            "timingPort" to openTiming(peerTimingPort),
            "eventPort" to openEvent(),
        )
        if (dict["keepAliveLowPower"] == true || dict["keepAliveLowPower"] == 1L) {
            response["keepAlivePort"] = openKeepAlive()
        }
        val features = viewAreaControl.negotiate(dict["features"] as? List<*>, (response["eventPort"] as? Number)?.toInt() != 0)
        negotiatedFeatures = features.toSet()
        debugLog("airplay SETUP acceptedFeatures=$features explicitProposal=${dict["features"] is List<*>}")
        val videoPlaybackEnabled = VideoInCar.FEATURE in features
        val videoDelivery = videoPlaybackAvailability.setFeatureEnabled(videoPlaybackEnabled)
        debugLog("airplay video playback negotiated=$videoPlaybackEnabled availability=$videoDelivery")
        response["enabledFeatures"] = features
        return RtspMessage.Response(
            headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
            body = BplistCodec.encode(response),
        )
    }

    private fun handleStreams(streams: List<*>): List<Any?> {
        val result = arrayListOf<Any?>()
        for (entry in streams) {
            val stream = asMap(entry) ?: continue
            val type = long(stream["type"])?.toInt() ?: continue
            debugLog("airplay SETUP stream type=$type keys=${stream.keys.sorted()}")
            when (type) {
                STREAM_TYPE_MAIN_SCREEN, STREAM_TYPE_ALT_SCREEN -> {
                    if (type == STREAM_TYPE_ALT_SCREEN && !viewAreaControl.acceptsAlternate()) {
                        debugLog("airplay alternate stream rejected: feature not enabled")
                        continue
                    }
                    val port = media.onScreen(this, type, stream)
                    debugLog("airplay screen stream type=$type dataPort=${port ?: "rejected"}")
                    if (port != null) {
                        activeStreams.add(type)
                        if (type == STREAM_TYPE_ALT_SCREEN) beginClusterContent()
                        else {
                            mainScreenToken = Any()
                            listener.onMainScreenReady(this)
                        }
                        viewAreaControl.screenReady(type)
                        result.add(linkedMapOf("type" to type, "dataPort" to port))
                    }
                }
                STREAM_TYPE_MAIN_AUDIO, STREAM_TYPE_ALT_AUDIO, STREAM_TYPE_MAIN_HIGH_AUDIO -> {
                    val streamResponse = media.onAudio(this, type, stream)
                    debugLog(
                        "airplay audio stream type=$type accepted=${streamResponse != null} " +
                            "dataPort=${streamResponse?.get("dataPort") ?: "none"} " +
                            "controlPort=${streamResponse?.get("controlPort") ?: "none"}",
                    )
                    if (streamResponse != null) {
                        activeStreams.add(type)
                        result.add(streamResponse)
                    }
                }
                STREAM_TYPE_DATA -> {
                    val uuid = stream["clientTypeUUID"] as? String
                    val extra = uuid?.let(com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes::findByUuid)
                        ?.takeIf { it != com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes.IAP_CHANNEL }
                    val seed = unsignedPlistDecimal(stream["seed"])
                    val clashesWithUltra = seed != null && synchronized(dataSeedLock) { seed in ultraDataSeeds }
                    val streamResponse = if (extra != null) ultraStreams.setup(stream, negotiatedFeatures)
                        else if (clashesWithUltra) null else media.onDataStream(this, stream)
                    debugLog(
                        "airplay data stream type=$type accepted=${streamResponse != null} " +
                            "dataPort=${streamResponse?.get("dataPort") ?: "none"}",
                    )
                    if (streamResponse != null) {
                        if (extra == null && seed != null && config.ultra?.negotiationAllowed == true) {
                            synchronized(dataSeedLock) { dataSeeds.add(seed) }
                        }
                        if (extra == null) activeStreams.add(type)
                        result.add(streamResponse)
                    }
                }
                else -> debugLog("airplay unsupported stream type=$type")
            }
        }
        return result
    }

    private fun handleCommand(request: RtspMessage.Request): RtspMessage.Response {
        val body = try {
            asMap(BplistCodec.decode(request.body)) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap<String, Any?>()
        }
        val type = string(body["type"])
        val params = asMap(body["params"]) ?: emptyMap()
        try {
            viewAreaControl.receive(type, params)
        } catch (error: IllegalArgumentException) {
            debugLog("airplay view-area command rejected type=$type reason=${error.message}")
            return RtspMessage.Response(status = 400)
        }
        debugLog("airplay command type=$type keys=${params.keys.sorted()}")
        val streamId = request.headers["x-apple-streamid"]?.toLongOrNull()
        val data = params["data"] as? ByteArray
        if (streamId != null && data != null) {
            runCatching { asMap(BplistCodec.decode(data)) }.getOrNull()
                ?.let { listener.onRemoteControlMessage(this, streamId, it) }
            return RtspMessage.Response(status = 200)
        }
        if (type == "requestUI" && string(params["url"]) == VideoInCar.UI_URL) {
            listener.onVideoPlaybackUiRequested(this)
        } else if (type == "requestUI") {
            expectMainScreenEnd()
            listener.onHostUiRequested(this)
        }
        resourceModes.observe(type, params)
        AirPlayPhoneState.observe(this, type, params)
        AirPlaySpeechState.observe(this, type, params)
        listener.onCommand(this, type, params)
        return RtspMessage.Response(status = 200)
    }

    private fun handleTeardown(request: RtspMessage.Request): RtspMessage.Response {
        val decodedBody = try {
            BplistCodec.decode(request.body)
        } catch (_: Exception) {
            null
        }
        val types = teardownStreamTypes(decodedBody)
        ultraStreams.teardown(decodedBody)

        debugLog(
            "airplay TEARDOWN types=${types ?: "all"} activeBefore=$activeStreams " +
                "body=${request.body.size} bytes",
        )

        val endingMainSource = if (types == null || STREAM_TYPE_MAIN_SCREEN in types) mainVideoSource else null
        // Revoke input first, but retain the producer's handoff until media cleanup claims it.
        // Its TCP EOF can arrive while onTeardown is waiting for the media lifecycle lock.
        if (types == null || STREAM_TYPE_MAIN_SCREEN in types) {
            expectMainScreenEnd()
            mainScreenToken = null
            renderedMainScreenToken = null
            receivedMainVideoSource = null
        }
        if (types == null || STREAM_TYPE_ALT_SCREEN in types) {
            viewAreaControl.screenClosed(STREAM_TYPE_ALT_SCREEN)
            clearClusterContent()
        }
        if (types == null) {
            media.onTeardown(this, types = null)
            activeStreams.clear()
        } else {
            val ending = types.filter { activeStreams.remove(it) }
            media.onTeardown(this, ending)
        }
        endingMainSource?.let(::mainScreenSourceEnded)
        if (STREAM_TYPE_ALT_SCREEN !in activeStreams) clearClusterContent()
        return RtspMessage.Response(status = 200)
    }

    private fun openTiming(peerPort: Int): Int {
        val port = ntp.listen()
        if (peerPort > 0) peerAddress?.let { ntp.start(it, peerPort) }
        return port
    }

    private fun openKeepAlive(): Int {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        keepAliveSocket = socket
        keepAliveThread = Thread({ runKeepAlive(socket) }, "airplay-keepalive").apply {
            isDaemon = true
            start()
        }
        return socket.localPort
    }

    private fun runKeepAlive(socket: DatagramSocket) {
        val buffer = ByteArray(512)
        while (!closed.get()) {
            try {
                socket.receive(DatagramPacket(buffer, buffer.size))
            } catch (_: Exception) {
                if (closed.get()) return
            }
        }
    }

    private fun openEvent(): Int {
        val server = ServerSocket(0, 50, InetAddress.getByName("::"))
        synchronized(eventLifecycleLock) {
            synchronized(eventWriteLock) {
                if (closed.get()) { safeClose(server); return 0 }
                val oldServer = eventServer
                val oldSocket = eventSocket
                eventServer = server
                eventSocket = null
                eventCipher = null
                safeClose(oldServer)
                safeClose(oldSocket)
                commandTracker.reset("event-replaced")
                synchronized(clusterContentLock) {
                    clusterContent = clusterContent?.copy(displayed = null, visibilityDelivered = false, token = Any())
                }
            }
            videoPlaybackAvailability.setEventReady(false)
            cancelClusterProgressCheck()
        }
        spawnEvent("airplay-event-accept") { acceptEvent(server) }
        return server.localPort
    }

    private fun teardown() {
        ultraStreams.close()
        videoPlaybackAvailability.setEventReady(false)
        videoPlaybackAvailability.setFeatureEnabled(false)
        ntp.close()
        safeClose(keepAliveSocket)
        keepAliveSocket = null
        keepAliveThread?.interrupt()
        keepAliveThread = null
        safeClose(eventServer)
        eventServer = null
        safeClose(eventSocket)
        eventSocket = null
        eventCipher = null
        eventThreads.forEach { it.interrupt() }
        eventThreads.clear()
    }

    private fun acceptEvent(server: ServerSocket) {
        var accepted: Socket? = null
        try {
            val socket = server.accept()
            accepted = socket
            socket.setSoLinger(true, 0)
            com.shilapi.xcertplay.network.TcpLiveness.configure(socket, config.fastPeerDetection) {
                debugLog("session=$diagnosticId event $it")
            }
            debugLog("airplay event connection accepted from ${socket.remoteSocketAddress}")
            synchronized(eventWriteLock) {
                if (closed.get() || eventServer !== server) { safeClose(socket); return }
                eventSocket = socket
                eventServer = null
                safeClose(server)
            }
            val shared = pairVerify.shared
            if (shared == null) {
                Log.e(TAG, "airplay event rejected: pair-verify shared secret unavailable")
                safeClose(socket)
                val closeOwner = synchronized(eventLifecycleLock) {
                    eventSocket === socket && closed.compareAndSet(false, true)
                }
                if (closeOwner) finishClose()
                return
            }
            val writeKey = AirPlayCrypto.hkdfSha512(
                shared,
                "Events-Salt".asciiBytes(),
                "Events-Write-Encryption-Key".asciiBytes(),
                32,
            )
            val readKey = AirPlayCrypto.hkdfSha512(
                shared,
                "Events-Salt".asciiBytes(),
                "Events-Read-Encryption-Key".asciiBytes(),
                32,
            )
            synchronized(eventLifecycleLock) {
                synchronized(eventWriteLock) {
                    if (closed.get() || eventSocket !== socket) { safeClose(socket); return }
                    eventCipher = ControlCipher(readKey, writeKey)
                    sendPendingNightModeLocked()
                }
                val videoDelivery = videoPlaybackAvailability.setEventReady(true)
                debugLog("airplay video event ready availability=$videoDelivery")
            }
            if (eventSocket !== socket || closed.get()) return
            resourceModes.flush()
            viewAreaControl.flush()
            reapplyClusterContent()
            runEventRead(socket)
        } catch (error: Exception) {
            val closeOwner = synchronized(eventLifecycleLock) {
                val current = eventServer === server || (accepted != null && eventSocket === accepted)
                current && closed.compareAndSet(false, true)
            }
            if (closeOwner) {
                Log.e(TAG, "airplay event accept failed", error)
                finishClose()
            }
        } finally {
            safeClose(server)
            if (accepted != null && eventSocket !== accepted) safeClose(accepted)
        }
    }

    private fun runEventRead(socket: Socket) {
        // Capture this peer's cipher. A superseded reader cannot consume the replacement's
        // encryption counters or revoke its readiness during a late EOF/finally callback.
        val cipher = synchronized(eventWriteLock) {
            if (eventSocket !== socket) return
            eventCipher ?: return
        }
        val cipherTimes = MessageByteTimes()
        val messageTimes = MessageByteTimes()
        val frameDeadline = IncompleteMessageDeadline("event encrypted frame", partialMessageTimeoutMillis) { safeClose(socket) }
        val messageDeadline = IncompleteMessageDeadline("event RTSP message", partialMessageTimeoutMillis) { safeClose(socket) }
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            var encrypted = ByteArray(0)
            var plaintext = ByteArray(0)
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (!closed.get() && eventSocket === socket) {
                eventTimeline.beginRead()
                val count = input.read(buffer)
                val receivedAt = System.nanoTime()
                eventTimeline.read()
                frameDeadline.check()
                messageDeadline.check()
                if (count < 0) { if (!closed.get()) eventTimeline.failure("read", "EOF"); break }
                if (count == 0) continue
                if (eventSocket !== socket) break
                encrypted += buffer.copyOf(count)
                cipherTimes.append(count, receivedAt)
                val decrypted = try {
                    cipher.decrypt(encrypted)
                } catch (error: Exception) {
                    eventTimeline.failure("decrypt", error.javaClass.simpleName)
                    Log.e(TAG, "airplay event decrypt failed encrypted=${encrypted.size}", error)
                    break
                }
                cipherTimes.transferAuthenticatedFrames(encrypted, encrypted.size - decrypted.rest.size, messageTimes)
                encrypted = decrypted.rest
                frameDeadline.update(cipherTimes.firstNanos)
                plaintext += decrypted.data
                val parsed = RtspMessage.parseMessages(plaintext)
                messageTimes.consume(plaintext.size - parsed.rest.size)
                plaintext = parsed.rest
                messageDeadline.update(messageTimes.firstNanos)
                for (message in parsed.messages) {
                    if (eventSocket !== socket || closed.get()) return
                    AirPlayCommandDiagnostics.response(message)
                    if (message.method.startsWith("RTSP/") || message.method.startsWith("HTTP/")) {
                        commandTracker.response(message)
                        continue
                    }
                    debugLog(
                        "airplay event rx ${message.method} ${message.path} cseq=${message.headers["cseq"] ?: "-"} body=${message.body.size}",
                    )
                    viewAreaControl.responseStarted()
                    var responseWritten = false
                    try {
                        val result = try {
                            if (message.method == "POST" && message.path.lowercase().endsWith("/command")) {
                                handleCommand(message)
                            } else RtspMessage.Response(status = 200)
                        } catch (error: Exception) {
                            Log.e(TAG, "airplay event handler failed ${message.method} ${message.path}", error)
                            RtspMessage.Response(status = 500)
                        }
                        val response = RtspMessage.buildResponse(message, result)
                        trace("airplay event tx status=${result.status ?: 200} " +
                            "contentBytes=${result.body.size} wireBytes=${response.size}")
                        synchronized(eventWriteLock) {
                            if (eventSocket !== socket || eventCipher !== cipher || closed.get()) return
                            eventTimeline.beginWrite()
                            output.write(cipher.encrypt(response))
                            output.flush()
                            eventTimeline.written()
                            responseWritten = true
                        }
                    } finally {
                        if (responseWritten) viewAreaControl.responseSent() else viewAreaControl.responseAborted()
                    }
                }
            }
        } catch (error: Exception) {
            val failure = messageDeadline.failureOr(frameDeadline.failureOr(error))
            if (!closed.get()) eventTimeline.failure("io", failure.javaClass.simpleName)
            if (!closed.get()) Log.e(TAG, "airplay event read failed", failure)
        } finally {
            frameDeadline.close()
            messageDeadline.close()
            var closeOwner = false
            val current = synchronized(eventLifecycleLock) {
                val ownsEvent = synchronized(eventWriteLock) {
                    if (eventSocket !== socket) false else {
                        eventSocket = null
                        eventCipher = null
                        commandTracker.reset("event-closed")
                        true
                    }
                }
                if (ownsEvent) {
                    videoPlaybackAvailability.setEventReady(false)
                    closeOwner = closed.compareAndSet(false, true)
                }
                ownsEvent
            }
            if (current) {
                eventTimeline.event("close initiator=event_reader")
                debugLog("airplay event connection closed")
            }
            safeClose(socket)
            if (closeOwner) finishClose()
        }
    }

    private fun spawnEvent(name: String, body: () -> Unit) {
        val thread = Thread({ try { body() } finally { eventThreads.remove(Thread.currentThread()) } }, name).apply { isDaemon = true }
        eventThreads.add(thread)
        thread.start()
    }

    private companion object {
        private val connectionIds = java.util.concurrent.atomic.AtomicLong()
        const val TAG = "xcertplay-usb"
        const val PLIST_CONTENT_TYPE = "application/x-apple-binary-plist"
        const val PAIRING_CONTENT_TYPE = "application/pairing+tlv8"
        const val OCTET_CONTENT_TYPE = "application/octet-stream"

        const val STREAM_TYPE_MAIN_SCREEN = 110
        const val STREAM_TYPE_ALT_SCREEN = 111
        const val STREAM_TYPE_MAIN_AUDIO = 100
        const val STREAM_TYPE_ALT_AUDIO = 101
        const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102
        const val STREAM_TYPE_DATA = 130

        const val READ_CHUNK_BYTES = 16 * 1024
        const val ZOOM_DIRECTION_IN = 0
        const val ZOOM_DIRECTION_OUT = 1
        const val EVENT_READY_POLL_MILLIS = 25L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/** Optional features use an intersection; iAPChannel declares the receiver's event-backed transport. */
internal fun setupEnabledFeatures(config: AirPlayConfig, proposed: List<*>?, eventAvailable: Boolean = true): List<String> {
    val supported = linkedSetOf("viewAreas")
    if (config.hevc) supported += "hevc"
    if (eventAvailable) {
        supported += "iAPChannel"
        if (config.cluster != null) {
            supported += "altScreen"
            if (config.receiverManagedClusterUi) supported += "uiContext"
        }
        if (config.videoInCar) supported += VideoInCar.FEATURE
        AirPlayFeatureNegotiation.capabilities(config, eventPortAvailable = true)
            .supportedFeatureSet.filter { it in ULTRA_PROTOCOL_FEATURES }
            .forEach { supported += it.wireName }
    }
    if (proposed == null) return supported.filter {
        it != "uiContext" && it != VideoInCar.FEATURE &&
            AirPlayFeatureCatalog.find(it) !in ULTRA_PROTOCOL_FEATURES
    }
    val accepted = proposed.filterIsInstance<String>().filterTo(linkedSetOf()) { it in supported }
    if (config.receiverManagedClusterUi && ("altScreen" in accepted) != ("uiContext" in accepted)) {
        accepted.removeAll(setOf("altScreen", "uiContext"))
    }
    // Peers may omit iAPChannel from their proposal. Retain the receiver capability, as
    // in the upstream handshake, so they can request type 130. This declaration is not
    // proof of a ready/authenticated runtime tunnel.
    if (eventAvailable) accepted += "iAPChannel"
    return accepted.toList()
}

/**
 * The stream types a TEARDOWN body closes, or null when it names none (the whole session). Several
 * type-130 streams can be open, the iAP tunnel (streamID 1) and video in car's data streams; tearing
 * down one of the latter must not close the iAP tunnel.
 */
internal fun teardownStreamTypes(body: Any?): List<Int>? {
    val entries = ((body as? Map<*, *>)?.get("streams") as? List<*>).orEmpty().mapNotNull { entry ->
        val stream = entry as? Map<*, *> ?: return@mapNotNull null
        val type = (stream["type"] as? Number)?.toInt() ?: return@mapNotNull null
        type to (stream["streamID"] as? Number)?.toLong()
    }
    if (entries.isEmpty()) return null
    return entries.filterNot { (type, streamId) -> type == 130 && streamId != null && streamId != 1L }.map { it.first }
}

internal fun safeClose(closeable: Closeable?) {
    try {
        closeable?.close()
    } catch (_: Exception) {
        // Best-effort close.
    }
}

private fun asMap(value: Any?): Map<String, Any?>? {
    val map = value as? Map<*, *> ?: return null
    val result = LinkedHashMap<String, Any?>(map.size)
    for ((key, entry) in map) result[key.toString()] = entry
    return result
}

private fun string(value: Any?): String = value as? String ?: ""

private fun long(value: Any?): Long? = (value as? Number)?.toLong()
