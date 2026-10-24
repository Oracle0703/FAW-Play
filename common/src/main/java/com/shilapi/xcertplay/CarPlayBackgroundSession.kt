package com.shilapi.xcertplay

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.ClusterContentProgress
import com.shilapi.xcertplay.hud.AutunnNavigationOutputs
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.network.WifiRadioStartup
import com.shilapi.xcertplay.network.WirelessAddressRecovery
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.PendingResourceClose
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.util.concurrent.Executors

/** The service owns connection intent and power monitoring; Activities only own their surfaces. */
internal object CarPlayBackgroundSession {
    private val main = Handler(Looper.getMainLooper())
    private var closer = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "autunn-session-close") }
    // One ordered owner for durable intent, saved display and background stack construction.
    private var stateIo = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "autunn-session-state").apply { isDaemon = true }
    }
    private var intentRevision = 0L
    private var durableIntentRevision = 0L
    private var configurationRevision = 0L
    private var preparationFailure: String? = null
    private var configurationLoading = false
    private val backgroundOwner = Any()
    private var app: Context? = null
    private var service: DiPlaySessionService? = null
    private var powerLog: SessionLogFile? = null
    private var automaticEntryRequested = false
    private var serviceResumeInitialized = false
    private var backgroundSetupRequired: String? = null
    private var wifiReadinessReceiver: BroadcastReceiver? = null
    private var backgroundEntrySequence = 0L
    private var backgroundEntryLaunch: BackgroundEntryLaunch? = null
    private val backgroundEntryRetryDelaysMillis = longArrayOf(1_000L, 3_000L)
    enum class BackgroundEntryStage { SUBMITTED, ACCEPTED, FOREGROUND, RETRY_WAIT, RETRY_READY, EXHAUSTED, VISIBLE }
    private const val BACKGROUND_ENTRY_FOREGROUND_TIMEOUT_MILLIS = 3_000L
    data class BackgroundEntryLaunch(
        val id: Long,
        internal val token: Any,
        val attempt: Int = 1,
        internal var stage: BackgroundEntryStage = BackgroundEntryStage.SUBMITTED,
        internal var host: Any? = null,
        internal var followUpAtMillis: Long = SystemClock.elapsedRealtime() + BACKGROUND_ENTRY_FOREGROUND_TIMEOUT_MILLIS,
    )
    data class BackgroundEntryFollowUp(val id: Long, val attempt: Int, val retryWait: Boolean, val dueAtMillis: Long)
    private var automaticEntryObserverOwner: Any? = null
    private var automaticEntryObserver: (() -> Unit)? = null
    private var uiDiagnosticLog: SessionLogFile? = null
    private var desired = true
    private var explicitRequestPending = false
    private var explicitUse = false
    private var manualDisconnect = false
    private var terminated = false
    private var starting = false
    private var preparedResume: (() -> Unit)? = null
    private var configuration: CarPlayResumeConfiguration? = null
    private var configuredStartup: CarPlayConfigurationStore.StartupConfiguration? = null
    private var recoveryGeneration = 0
    private var retryPending = false
    private var retryDueAtMillis = 0L
    private var retryStopped = false
    private var retryRequiresUserAction = false
    private var phoneMonitor: SelectedPhoneMonitor? = null
    private var phoneState = SelectedPhoneState.UNKNOWN
    private var automaticBootstrapAttempted = false
    private var retryBudget = WirelessStartupRetryBudget()
    private var startupDeadlineCheck: Runnable? = null
    private var startupDeadlineAt: Long? = null
    private var ignitionMonitor: HongqiIgnitionMonitor? = null
    private var ignitionDebouncer = IgnitionStateDebouncer()
    private var vehicleCandidateRecheck: Runnable? = null
    private var ignitionOwner: Any? = null
    private var ignitionListener: ((Boolean?) -> Unit)? = null
    private var ignitionReady: (() -> Unit)? = null
    private var ignitionState: Boolean? = null
    private var restored = false
    private var useGeneration = 0L
    private var lastStartupGate: String? = null
    private var pendingReuse: CarPlayController? = null
    private var activeSession: AirPlaySession? = null
    private var screenOwnerSession: AirPlaySession? = null
    private var observedScreenOwner: com.shilapi.xcertplay.airplay.AirPlayResourceModes.Entity? = null
    data class ChannelReadiness(
        val airPlayConnected: Boolean = false,
        val mainFrameRendered: Boolean = false,
        val wirelessRuntimeAuthenticated: Boolean = false,
        val navigationDataReceived: Boolean = false,
        val cluster: ClusterContentProgress? = null,
    ) {
        internal fun withCluster(incoming: ClusterContentProgress?): ChannelReadiness {
            incoming ?: return this
            val before = cluster ?: return copy(cluster = incoming)
            if (incoming.revision < before.revision) return this
            if (incoming.revision > before.revision) return copy(cluster = incoming)
            if (incoming.stream != before.stream) return this
            // Writer and decoder callbacks can be posted in either order. Within one
            // request revision these are monotonic facts; show/hide starts a new revision.
            val received = before.validVideoReceived || incoming.validVideoReceived
            val rendered = before.frameRendered || incoming.frameRendered
            return copy(cluster = before.copy(
                requestWritten = before.requestWritten || incoming.requestWritten,
                validVideoReceived = received,
                frameRendered = rendered,
                attempts = maxOf(before.attempts, incoming.attempts),
                failure = if (received || rendered) null else incoming.failure ?: before.failure,
            ))
        }
    }
    private var readiness = ChannelReadiness()
    @Synchronized fun channelReadiness(): ChannelReadiness = readiness
    private fun updateReadiness(value: ChannelReadiness) {
        if (readiness == value) return
        readiness = value
        recordUiDiagnostic("channel readiness=$value")
    }
    private var projectionUiOwner: Any? = null
    private var startupOwner: Any? = null
    private var foregroundStartupReady = true
    private var useSawIgnitionOn = false
    private var restoredIgnitionPending = false
    private var lastIgnitionSample: Boolean? = null
    private var ignitionSampleLogged = false
    @Volatile var ignitionOff = false
        private set
    @Volatile var active = false
    private var owner: Any? = null
    private var stopAction: (((() -> Unit)) -> Unit)? = null
    private var stopping = false
    private val stopWaiters = mutableListOf<() -> Unit>()
    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var width = 0
    private var height = 0
    private var display: CarPlaySessionDisplay? = null

    private class Retirement(val controller: CarPlayController?, val sink: AndroidMediaSink?, val reason: String) {
        @Volatile var sinkReleased = sink == null
        @Volatile var mediaCloseCompleted = false
        var attemptRunning = false
        var attempt = 0
        var quarantined = false
        var controllerObservationPending = false
        val closes = PendingResourceClose { name, result ->
            if (name == "audio" && result.released) main.post { mediaClosed(this) }
        }
        val completions = mutableListOf<() -> Unit>()
    }
    private val retiring = mutableListOf<Retirement>()
    private val retirementWaiters = mutableListOf<() -> Unit>()

    @Synchronized fun automaticEntryPending(): Boolean = automaticEntryRequested
    @Synchronized fun watchAutomaticEntry(candidate: Any, changed: () -> Unit) {
        automaticEntryObserverOwner = candidate
        automaticEntryObserver = changed
        notifyAutomaticEntryObserver()
    }
    @Synchronized fun stopAutomaticEntryWatch(candidate: Any) {
        if (automaticEntryObserverOwner !== candidate) return
        automaticEntryObserverOwner = null
        automaticEntryObserver = null
        service?.scheduleAutomaticEntry()
    }
    private fun notifyAutomaticEntryObserver() {
        service?.scheduleAutomaticEntry()
        val observer = automaticEntryObserver ?: return
        main.post {
            if (synchronized(this) { automaticEntryObserver === observer }) observer()
        }
    }
    @Synchronized fun automaticConnectAllowed(): Boolean = !manualDisconnect && !ignitionOff && !terminated
    @Synchronized fun automaticEntryReady(): Boolean = automaticEntryRequested && connectedProjectionReady() && controller?.mainScreenAllowed() != false
    @Synchronized fun connectedProjectionReady(): Boolean = canKeepSession() && !stopping &&
        retiring.isEmpty() && controller?.activeAirPlaySessionToken() != null
    /**
     * Connection survives HOME and main-video withdrawal. Use the same live-session boundary
     * for entry and presentation; a missing first frame is not a disconnected software state.
     * The background listener's active state also covers a screen-transfer acknowledgement
     * while the controller's current-session getter is temporarily empty.
     */
    @Synchronized fun connectedSessionAvailable(): Boolean = canKeepSession() && !stopping &&
        retiring.isEmpty() &&
        (active || controller?.currentActiveAirPlaySession() != null ||
            controller?.activeAirPlaySessionToken() != null)
    @Synchronized fun launcherEntryBelongsToHost(): Boolean = connectedSessionAvailable()
    @Synchronized fun requestAutomaticEntry() {
        if (manualDisconnect || ignitionOff || terminated) return
        val changed = !automaticEntryRequested
        automaticEntryRequested = true
        if (changed) backgroundEntryLaunch = null
        recordUiDiagnostic("auto-entry pending=true")
        if (changed) notifyAutomaticEntryObserver()
    }
    @Synchronized fun cancelAutomaticEntry(reason: String) {
        if (!automaticEntryRequested) return
        automaticEntryRequested = false
        recordUiDiagnostic("auto-entry canceled reason=$reason")
        notifyAutomaticEntryObserver()
    }
    @Synchronized fun takeAutomaticEntryIfReady(reason: String): Boolean {
        if (!automaticEntryReady()) return false
        automaticEntryRequested = false
        backgroundEntryLaunch?.takeIf { it.token === controller?.activeAirPlaySessionToken() }
            ?.stage = BackgroundEntryStage.VISIBLE
        recordUiDiagnostic("auto-entry completed reason=$reason reuseSession=true")
        notifyAutomaticEntryObserver()
        return true
    }
    /** A Host which has actually reached the foreground owns its rendering wait. */
    @Synchronized fun claimBackgroundEntry(requester: Any? = null): BackgroundEntryLaunch? {
        if (automaticEntryObserverOwner !== requester) return null
        backgroundEntryLaunch?.takeIf { isCurrentBackgroundEntry(it) &&
            it.stage == BackgroundEntryStage.RETRY_READY }?.host?.let { host ->
            // A late onCreate during backoff may install a provisional owner again.
            // It still needs this retry unless resume+focus confirmed foreground.
            if (projectionUiOwner === host) {
                projectionUiOwner = null
                releaseForegroundStartup(host)
            }
        }
        if (!automaticEntryRequested || !canKeepSession() || stopping || retiring.isNotEmpty() ||
            projectionUiOwner != null) return null
        val token = controller?.activeAirPlaySessionToken() ?: return null
        val previous = backgroundEntryLaunch?.takeIf { it.token === token }
        if (previous != null && previous.stage != BackgroundEntryStage.RETRY_READY) return null
        val attempt = previous?.attempt?.plus(1) ?: 1
        if (attempt > backgroundEntryRetryDelaysMillis.size + 1) return null
        // A late delivery from an earlier submission still belongs to this exact phone/intent.
        // Sharing its id avoids rejecting that valid Host while the retry is being dispatched.
        return BackgroundEntryLaunch(previous?.id ?: ++backgroundEntrySequence, token, attempt)
            .also { backgroundEntryLaunch = it }
    }

    private fun isCurrentBackgroundEntry(launch: BackgroundEntryLaunch): Boolean =
        automaticEntryRequested && canKeepSession() && !stopping && retiring.isEmpty() &&
            controller?.activeAirPlaySessionToken() === launch.token

    @Synchronized fun backgroundEntryFollowUp(): BackgroundEntryFollowUp? {
        val launch = backgroundEntryLaunch ?: return null
        if (!isCurrentBackgroundEntry(launch)) return null
        return when (launch.stage) {
            BackgroundEntryStage.SUBMITTED, BackgroundEntryStage.ACCEPTED, BackgroundEntryStage.RETRY_WAIT ->
                BackgroundEntryFollowUp(launch.id, launch.attempt,
                    launch.stage == BackgroundEntryStage.RETRY_WAIT, launch.followUpAtMillis)
            else -> null
        }
    }

    /** Receipt alone does not prove that Android brought the task to the foreground. */
    @Synchronized fun deferBackgroundEntryRetry(id: Long, reason: String, attempt: Int? = null): Long? {
        val launch = backgroundEntryLaunch ?: return null
        if (launch.id != id || !isCurrentBackgroundEntry(launch) ||
            attempt != null && launch.attempt != attempt ||
            launch.stage !in setOf(BackgroundEntryStage.SUBMITTED, BackgroundEntryStage.ACCEPTED)) return null
        val delay = backgroundEntryRetryDelaysMillis.getOrNull(launch.attempt - 1)
        launch.stage = if (delay == null) BackgroundEntryStage.EXHAUSTED else BackgroundEntryStage.RETRY_WAIT
        if (delay != null) launch.followUpAtMillis = SystemClock.elapsedRealtime() + delay
        // Only revoke this delivery's provisional ownership. A settings page or a Host
        // which confirmed foreground must never be displaced by a startup timeout.
        launch.host?.let { host ->
            if (projectionUiOwner === host) {
                projectionUiOwner = null
                releaseForegroundStartup(host)
            }
        }
        recordUiDiagnostic("background auto-entry not foreground id=$id attempt=${launch.attempt} " +
            "reason=$reason retryDelayMs=${delay ?: "none"}; notification entry available")
        return delay
    }

    @Synchronized fun prepareBackgroundEntryRetry(id: Long, attempt: Int? = null): Boolean {
        val launch = backgroundEntryLaunch ?: return false
        if (launch.id != id || !isCurrentBackgroundEntry(launch) ||
            attempt != null && launch.attempt != attempt ||
            launch.stage != BackgroundEntryStage.RETRY_WAIT) return false
        launch.stage = BackgroundEntryStage.RETRY_READY
        return true
    }

    /** Reject a queued launch after cancellation, parking, or replacement of the phone stream. */
    @Synchronized fun acceptBackgroundEntry(intent: Intent, host: Any? = null): Boolean {
        if (!intent.hasExtra(BACKGROUND_ENTRY_ID)) return true
        val id = intent.getLongExtra(BACKGROUND_ENTRY_ID, -1L)
        intent.removeExtra(BACKGROUND_ENTRY_ID)
        val launch = backgroundEntryLaunch ?: return false
        if (id != launch.id || !isCurrentBackgroundEntry(launch)) return false
        if (host != null) launch.host = host
        if (launch.stage == BackgroundEntryStage.SUBMITTED) {
            launch.stage = BackgroundEntryStage.ACCEPTED
            recordUiDiagnostic("background auto-entry Host accepted id=$id attempt=${launch.attempt}")
            notifyAutomaticEntryObserver()
        }
        return true
    }

    /** Called only after this exact Host is both resumed and focused, before any first frame. */
    @Synchronized fun confirmBackgroundEntryForeground(host: Any): Boolean {
        val launch = backgroundEntryLaunch ?: return false
        if (launch.host !== host || !isCurrentBackgroundEntry(launch) || automaticEntryObserverOwner != null ||
            projectionUiOwner != null && projectionUiOwner !== host) return false
        // The foreground deadline may have released this provisional owner while focus
        // was delayed. A real late focus can still cancel backoff without another resume.
        projectionUiOwner = host
        if (launch.stage != BackgroundEntryStage.FOREGROUND) {
            launch.stage = BackgroundEntryStage.FOREGROUND
            recordUiDiagnostic("background auto-entry Host foreground id=${launch.id} attempt=${launch.attempt}")
            notifyAutomaticEntryObserver()
        }
        return true
    }

    const val BACKGROUND_ENTRY_ID = "background_automatic_entry_id"

    @Synchronized fun recordUiDiagnostic(message: String) {
        val line = "${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} cycle=$useGeneration " +
            "elapsedRealtimeMs=${SystemClock.elapsedRealtime()} $message"
        Log.i("Autunn-Session", line)
        AsyncDiagnosticLog.append(powerLog ?: uiDiagnosticLog, line)
    }
    private fun initialize(context: Context) {
        app = context.applicationContext
        if (powerLog == null) powerLog = AsyncDiagnosticLog.open(
            File(File(context.filesDir, "logs"), "power.log"), "Autunn Play power/session log started")
        com.shilapi.xcertplay.diagnostics.ClientDiagnostics.attach { event ->
            AsyncDiagnosticLog.append(powerLog, event)
        }
        if (!restored) {
            restored = true
            val family = context.getSharedPreferences("autunn_connection_success", Context.MODE_PRIVATE)
                .getString("address_family", null)
            retryBudget.addressRecovery.restore(WirelessAddressRecovery.Snapshot(family, confirmedFamily = family))
            desired = DiPlayPreferences.autoConnect(context)
            SessionRecoveryRecord.load(context)?.let { saved ->
                useGeneration = saved.useGeneration
                manualDisconnect = saved.manualDisconnect
                ignitionOff = saved.parked
                terminated = saved.terminated
                explicitUse = saved.explicitUse
                automaticBootstrapAttempted = saved.automaticBootstrapAttempted
                desired = saved.desired && !manualDisconnect && !ignitionOff && !terminated &&
                    (explicitUse || DiPlayPreferences.autoConnect(context))
                useSawIgnitionOn = saved.useSawIgnitionOn
                restoredIgnitionPending = desired && useSawIgnitionOn
                retryBudget.restore(saved.retries,
                    WirelessAddressRecovery.Snapshot(saved.addressFamily, saved.addressFamilySwitched, saved.confirmedAddressFamily), saved.startupDeadlineMillis)
                retryStopped = saved.retryStopped
                retryRequiresUserAction = saved.retryRequiresUserAction
                if (saved.parked) driverDoorSequence.beginParkingCycle()
            }
        }
    }
    private fun persistIntent() {
        val context = app ?: return
        val revision = ++intentRevision
        val address = retryBudget.addressRecovery.snapshot()
        val value = SessionRecoveryRecord(useGeneration, desired, manualDisconnect, ignitionOff,
            terminated, retryBudget.retries, retryStopped, useSawIgnitionOn, explicitUse, retryRequiresUserAction,
            address.preferredFamily, address.switched, retryBudget.deadlineMillis, automaticBootstrapAttempted, address.confirmedFamily)
        address.confirmedFamily?.let { family ->
            context.getSharedPreferences("autunn_connection_success", Context.MODE_PRIVATE).edit()
                .putString("address_family", family).apply()
        }
        writeIntent(context, revision, value)
    }
    private fun writeIntent(context: Context, revision: Long, value: SessionRecoveryRecord) {
        stateIo.execute {
            val saved = runCatching { SessionRecoveryRecord.save(context, value) }.getOrDefault(false)
            completeOnMain { synchronized(this) {
                if (saved) {
                    durableIntentRevision = maxOf(durableIntentRevision, revision)
                    if (stopping && retiring.isEmpty()) finishStop()
                    reconcile()
                } else if (revision == intentRevision) {
                    recordUiDiagnostic("control intent persistence pending; new connection remains blocked")
                    main.postDelayed({ synchronized(this) {
                        if (revision == intentRevision) writeIntent(context, revision, value)
                    } }, 1_000)
                }
            } }
        }
    }
    private fun completeOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }
    /** Only a fresh foreground launcher/home entry may reopen a task-terminated use. */
    @Synchronized fun reopenFromLauncher(context: Context): Boolean {
        initialize(context)
        if (!terminated || ignitionOff || !DiPlayPreferences.autoConnect(context) ||
            DiPlayPreferences.phoneAddress(context).isNullOrBlank()) return false
        // Task removal stops this use. A later launcher entry starts a new automatic use;
        // ordinary service/phone events and an explicit in-app disconnect cannot do so.
        terminated = false
        preparationFailure = null
        serviceResumeInitialized = false
        beginVehicleUse("launcher-reopen", confirmedOn = false)
        return true
    }
    @Synchronized fun requestAutomaticConnection(context: Context): Boolean {
        initialize(context)
        if (terminated || manualDisconnect || !DiPlayPreferences.autoConnect(context)) return false
        if (ignitionOff) {
            // A same-boot process replacement can restore parking before any service exists.
            // Keep the connection blocked, but let fresh vehicle observations resume the use.
            resumeService(context)
            try {
                context.startForegroundService(Intent(context, DiPlaySessionService::class.java))
            } catch (error: RuntimeException) {
                recordUiDiagnostic("parked vehicle watch service could not start=${error.javaClass.simpleName}")
            }
            return false
        }
        desired = true
        requestAutomaticEntry()
        ensureVehicleMonitor()
        refreshPhonePresence()
        persistIntent()
        main.post { reconcile() }
        return true
    }
    @Synchronized fun requestConnection(context: Context) {
        initialize(context)
        terminated = false
        desired = true
        explicitRequestPending = true
        explicitUse = true
        manualDisconnect = false
        ignitionOff = false
        driverDoorSequence.reset()
        ignitionDebouncer.reset()
        ignitionState = null
        useSawIgnitionOn = false
        restoredIgnitionPending = false
        useGeneration++
        automaticBootstrapAttempted = false
        retryBudget.manualRetry()
        retryStopped = false
        retryRequiresUserAction = false
        recoveryGeneration++
        retryPending = false
        pendingReuse = null
        preparationFailure = null
        ensureVehicleMonitor()
        persistIntent()
        recordUiDiagnostic("connection requested explicitly ignition=$ignitionState closing=${retiring.size}")
        main.post { reconcile() }
    }
    @Synchronized fun configureRecovery(context: Context, value: CarPlayResumeConfiguration) {
        initialize(context)
        if (configuration == null || configuredStartup?.matches(value) != true) {
            configuration = value
            configuredStartup = CarPlayConfigurationStore.StartupConfiguration(value)
            configurationRevision++
            backgroundSetupRequired = null
            preparationFailure = null
            stateIo.execute { runCatching { CarPlayConfigurationStore.save(context, value) }
                .onFailure { recordUiDiagnostic("display persistence failed=${it.javaClass.simpleName}") } }
        }
        // A state/cleanup notification can reach the Host while its previous submission is
        // still being constructed. Keep that construction valid unless parameters changed.
        ensureVehicleMonitor()
        refreshPhonePresence()
        main.post { reconcile() }
        refreshService()
    }
    @Synchronized fun startBlockReason(): String? = when {
        terminated || manualDisconnect -> "manual-stop"
        ignitionOff -> "parked"
        !desired -> "idle"
        restoredIgnitionPending -> "checking-vehicle"
        ignitionDebouncer.offCandidatePending() -> "checking-vehicle-stop"
        retiring.any { it.quarantined } -> "cleanup-blocked"
        stopping || retiring.isNotEmpty() -> "closing"
        durableIntentRevision < intentRevision -> "saving-control"
        preparationFailure != null -> "preparation-failed"
        retryStopped -> "retry-stopped"
        else -> null
    }
    @Synchronized fun canStart(): Boolean = !starting && startBlockReason() == null
    @Synchronized fun preparationError(): String? = preparationFailure

    /** Activity ownership can outlive its page; only a visible/preparing Host blocks entry. */
    @Synchronized fun setProjectionUiVisible(candidate: Any, visible: Boolean) {
        if (visible) projectionUiOwner = candidate
        else {
            if (projectionUiOwner === candidate) {
                projectionUiOwner = null
                backgroundEntryLaunch?.takeIf { isCurrentBackgroundEntry(it) &&
                    it.host === candidate && it.stage in setOf(BackgroundEntryStage.ACCEPTED,
                        BackgroundEntryStage.FOREGROUND) }?.let { launch ->
                    launch.stage = if (launch.attempt <= backgroundEntryRetryDelaysMillis.size)
                        BackgroundEntryStage.RETRY_READY else BackgroundEntryStage.EXHAUSTED
                    recordUiDiagnostic("background auto-entry Host left before visible id=${launch.id} " +
                        "attempt=${launch.attempt} stage=${launch.stage}")
                }
                service?.scheduleAutomaticEntry()
            }
            releaseForegroundStartup(candidate)
        }
    }

    /** Only the visible host can hold creation while its viewport or permissions settle. */
    @Synchronized fun setForegroundStartupReady(candidate: Any, ready: Boolean) {
        // A retained, paused Host can still receive viewport/vehicle callbacks.
        if (candidate is CarPlayHostActivity && projectionUiOwner !== candidate) return
        startupOwner = candidate
        foregroundStartupReady = ready
        if (ready) main.post { reconcile() }
    }
    @Synchronized fun releaseForegroundStartup(candidate: Any) {
        if (startupOwner !== candidate) return
        startupOwner = null
        foregroundStartupReady = true
        main.post { reconcile() }
    }

    private fun ensureVehicleMonitor() {
        val context = app ?: return
        if (ignitionMonitor == null && !terminated) {
            ignitionMonitor = HongqiIgnitionMonitor(context, ::onIgnitionSample,
                SystemClock::elapsedRealtime, ::recordUiDiagnostic,
                onDriverDoorObservation = { observation ->
                    recordDriverDoorObservation(observation)
                }).also { it.start() }
        }
    }
    private fun refreshPhonePresence() {
        val context = app ?: return
        if (terminated) return
        val current = phoneMonitor
        if (current != null) current.refresh()
        else {
            val monitor = SelectedPhoneMonitor(context, ::onPhoneState, ::recordUiDiagnostic)
            phoneMonitor = monitor
            monitor.start()
        }
    }
    // An owned controller that already established AirPlay can recover over its current
    // wireless network after Bluetooth handoff. Gate new Bluetooth bootstrap, not that reuse.
    private fun waitingForPhone(): Boolean = !explicitUse && activeSession == null &&
        configuration?.runtime?.transport == CarPlayTransport.WIRELESS &&
        phoneState in setOf(SelectedPhoneState.DISCONNECTED, SelectedPhoneState.UNAVAILABLE) &&
        !(phoneState == SelectedPhoneState.DISCONNECTED && !automaticBootstrapAttempted &&
            BluetoothBootstrapEligibility.canInitiate(app, configuration?.runtime?.wirelessBluetoothDeviceAddress))

    @Synchronized private fun onPhoneState(next: SelectedPhoneState) {
        if (terminated) return
        val previous = phoneState
        phoneState = next
        if (next == SelectedPhoneState.CONNECTED && controller != null && !active &&
            configuration?.runtime?.transport == CarPlayTransport.WIRELESS && retryBudget.deadlineMillis == null)
            armStartupDeadline()
        // Bluetooth can be released after wireless CarPlay is established. This observation
        // admits new automatic work only; it never tears down a healthy projection.
        if (next == SelectedPhoneState.CONNECTED && previous in setOf(
                SelectedPhoneState.DISCONNECTED, SelectedPhoneState.UNAVAILABLE) &&
            !explicitUse && canKeepSession() && app?.let(DiPlayPreferences::autoConnect) == true &&
            !retryRequiresUserAction && !active) {
            // Repeated Bluetooth departures/arrivals cannot replenish an unfinished startup.
            if (retryBudget.deadlineMillis == null) {
                retryStopped = false
                retryBudget.manualRetry()
            }
            persistIntent()
            // An already queued recovery retains its controller and generation.
            // Preserve the user's desktop/settings choice: arrival is not an entry request.
            recordUiDiagnostic("selected phone arrived; automatic connection may resume")
        }
        refreshService()
        notifyUi()
        main.post { reconcile() }
    }

    private var lastDriverDoor: Int? = null
    private var lastDriverDoorHealth: VehicleSignalHealth? = null
    // Enable only with a mapping verified on the deployed vehicle firmware.
    private val driverDoorSequence = DriverDoorParkingSequence(DriverDoorSemantics.forCurrentPlatform())
    @Synchronized private fun recordDriverDoorObservation(observation: DriverDoorObservation) {
        if (observation.raw != lastDriverDoor || observation.health != lastDriverDoorHealth) {
            lastDriverDoor = observation.raw
            lastDriverDoorHealth = observation.health
            recordUiDiagnostic("driver door raw=${observation.raw} health=${observation.health} verifiedMapping=${DriverDoorSemantics.forCurrentPlatform() != null}")
        }
        when (driverDoorSequence.update(observation)) {
            DriverDoorParkingEvent.RETURN_DOOR_OPENED -> vehicleUseResumed("verified-driver-door-return")
            DriverDoorParkingEvent.EXIT_DOOR_CYCLE -> recordUiDiagnostic("vehicle departure door cycle observed")
            null -> Unit
        }
    }
    /** Called only by an adapter whose return/wake sequence is verified for this firmware. */
    @Synchronized fun vehicleUseResumed(reason: String) {
        // A door/wake notification cannot cancel the user's explicit stop. A separately
        // confirmed new power cycle retains its existing ignition-on policy below.
        if (manualDisconnect || terminated) return
        beginVehicleUse(reason, confirmedOn = false)
    }
    private fun beginVehicleUse(reason: String, confirmedOn: Boolean) {
        val context = app ?: return
        if (terminated || !DiPlayPreferences.autoConnect(context)) return
        if (desired && !ignitionOff && !manualDisconnect) return
        useGeneration++
        automaticBootstrapAttempted = false
        ignitionDebouncer.reset(confirmedOn)
        ignitionState = if (confirmedOn) true else null
        useSawIgnitionOn = confirmedOn
        restoredIgnitionPending = false
        ignitionOff = false
        manualDisconnect = false
        desired = true
        explicitRequestPending = false
        explicitUse = false
        driverDoorSequence.reset()
        recoveryGeneration++
        retryPending = false
        pendingReuse = null
        retryStopped = false
        retryRequiresUserAction = false
        retryBudget.manualRetry()
        refreshPhonePresence()
        requestAutomaticEntry()
        persistIntent()
        recordUiDiagnostic("new vehicle use reason=$reason use=$useGeneration closing=${retiring.size}")
        notifyUi()
        reconcile()
    }

    @Synchronized fun watchIgnition(
        context: Context,
        onState: (Boolean?) -> Unit,
        onDiagnostic: (String) -> Unit = {},
        onReady: () -> Unit = {},
    ) {
        initialize(context)
        ignitionOwner = context
        ignitionListener = onState
        ignitionReady = onReady
        onState(ignitionState)
        ensureVehicleMonitor()
        onDiagnostic("vehicle ignition monitor follows service lifetime")
    }
    @Synchronized private fun onIgnitionSample(sample: Boolean?) {
        if (terminated) return
        if (!ignitionSampleLogged || sample != lastIgnitionSample) {
            ignitionSampleLogged = true
            lastIgnitionSample = sample
            recordUiDiagnostic("vehicle ignition sample=${sample?.let { if (it) "on" else "off" } ?: "unknown"}")
        }
        val wasCandidate = ignitionDebouncer.offCandidatePending()
        val confirmed = ignitionDebouncer.update(sample)
        val candidate = ignitionDebouncer.offCandidatePending()
        updateVehicleCandidateRecheck()
        if (candidate != wasCandidate) {
            recordUiDiagnostic("vehicle stop candidate=$candidate; active media unchanged until confirmed")
            notifyUi()
            main.post { reconcile() }
        }
        if (confirmed == ignitionState) return
        val previous = ignitionState
        ignitionState = confirmed
        if (confirmed == true) {
            useSawIgnitionOn = true
            restoredIgnitionPending = false
        }
        if (confirmed == false && (previous == true || useSawIgnitionOn)) {
            useSawIgnitionOn = false
            restoredIgnitionPending = false
            ignitionOff = true
            desired = false
            explicitRequestPending = false
            explicitUse = false
            driverDoorSequence.beginParkingCycle()
            recoveryGeneration++
            retryPending = false
            pendingReuse = null
            retryBudget.disconnected()
            cancelAutomaticEntry("ignition-off")
            AutunnNavigationOutputs.endNow()
            val old = snapshot()
            if (old != null) {
                old.controller.pauseMediaForVehicleStop()
                clear(old.controller)
                retire(old.controller, old.sink, "ignition-off")
            }
            // A failure can already be retiring this same use; parking upgrades it to a full stop.
            retiring.forEach { it.controller?.pauseMediaForVehicleStop() }
        } else if (confirmed == true && (previous == false || ignitionOff)) {
            beginVehicleUse("ignition-on", confirmedOn = true)
        }
        persistIntent()
        recordUiDiagnostic("vehicle ignition confirmed=$confirmed desired=$desired closing=${retiring.size}")
        ignitionListener?.invoke(confirmed)
        notifyUi()
        refreshService()
        reconcile()
    }
    /** A lost sampler must not leave a temporary recovery gate latched indefinitely. */
    private fun updateVehicleCandidateRecheck() {
        if (!ignitionDebouncer.offCandidatePending()) {
            vehicleCandidateRecheck?.let(main::removeCallbacks)
            vehicleCandidateRecheck = null
            return
        }
        if (vehicleCandidateRecheck != null) return
        val check = object : Runnable {
            override fun run() = synchronized(this@CarPlayBackgroundSession) {
                if (vehicleCandidateRecheck !== this) return@synchronized
                vehicleCandidateRecheck = null
                if (terminated) return@synchronized
                if (ignitionDebouncer.offCandidatePending()) updateVehicleCandidateRecheck()
                else {
                    recordUiDiagnostic("vehicle stop candidate expired; recovery allowed by current intent")
                    notifyUi()
                    reconcile()
                }
            }
        }
        vehicleCandidateRecheck = check
        main.postDelayed(check, 250)
    }
    /** Unsubscribe a disappearing UI without stopping the service's vehicle monitor. */
    @Synchronized fun stopIgnitionWatch(expectedOwner: Any) {
        releaseForegroundStartup(expectedOwner)
        if (ignitionOwner !== expectedOwner) return
        ignitionOwner = null
        ignitionListener = null
        ignitionReady = null
    }
    @Synchronized fun handoffToBackground(
        expectedOwner: Any,
        pendingRecovery: Boolean = false,
        recoveryStopped: Boolean = false,
        startupBudget: WirelessStartupRetryBudget? = null,
    ) {
        releaseForegroundStartup(expectedOwner)
        if (owner !== expectedOwner) return
        val current = snapshot() ?: return
        if (startupBudget != null) retryBudget = startupBudget
        retryStopped = retryStopped || recoveryStopped
        owner = backgroundOwner
        stopAction = { done -> stopBackground(done) }
        uiDiagnosticLog = null
        current.sink.setScreenStreamActiveChangedListener { _, _ -> }
        current.sink.setAudioDiagnosticListener { AsyncDiagnosticLog.append(powerLog, it) }
        HongqiWheelControls.attachDiagnosticLog(current.controller, powerLog)
        current.controller.attachUi(backgroundListener(current.controller), backgroundStatus(current.controller))
        recordUiDiagnostic("session handed to background without Activity")
        if (pendingRecovery && !retryStopped) backgroundLost(current.controller, "host-recovery-handoff")
    }
    @Synchronized fun claimForeground(candidate: Any, expected: CarPlayController,
                                      diagnosticLog: SessionLogFile? = null): Boolean {
        if (controller !== expected || !canKeepSession()) return false
        owner = candidate
        uiDiagnosticLog = diagnosticLog
        stopAction = { done -> stopBackground(done) }
        return true
    }
    @Synchronized fun startupRetryBudget(): WirelessStartupRetryBudget = retryBudget
    @Synchronized fun retryStopped(): Boolean = retryStopped
    @Synchronized fun retryManually() {
        val context = app ?: return
        requestConnection(context)
        requestAutomaticEntry()
        val current = controller
        if (current != null) {
            val media = sink
            clear(current)
            retire(current, media, "manual-retry")
        }
        main.post { reconcile() }
    }
    @Synchronized fun attachForegroundUi(expected: CarPlayController, listener: AirPlaySessionListener,
                                         statusReporter: (CarPlayStatus) -> Unit) {
        if (controller !== expected) return
        val central = backgroundListener(expected)
        val combined = object : AirPlaySessionListener by listener {
            override fun onSessionActive(session: AirPlaySession) {
                central.onSessionActive(session)
                if (controller === expected) listener.onSessionActive(session)
            }
            override fun onMainScreenVideoReceived(session: AirPlaySession) {
                central.onMainScreenVideoReceived(session)
                if (controller === expected) listener.onMainScreenVideoReceived(session)
            }
            override fun onMainScreenReady(session: AirPlaySession) {
                central.onMainScreenReady(session)
                if (controller === expected) listener.onMainScreenReady(session)
            }
            override fun onSessionEnded(session: AirPlaySession) {
                central.onSessionEnded(session)
                if (controller === expected) listener.onSessionEnded(session)
            }
            override fun onSessionDisconnected(session: AirPlaySession) {
                central.onSessionDisconnected(session)
                if (controller === expected) listener.onSessionDisconnected(session)
            }
            override fun onTransportError(message: String) {
                central.onTransportError(message)
                if (controller === expected) listener.onTransportError(message)
            }
            override fun onVideoFrameRendered(session: AirPlaySession) {
                central.onVideoFrameRendered(session)
                if (controller === expected) listener.onVideoFrameRendered(session)
            }
            override fun onVideoFrameRendered(session: AirPlaySession, screenType: Int) {
                central.onVideoFrameRendered(session, screenType)
                if (controller === expected) listener.onVideoFrameRendered(session, screenType)
            }
            override fun onWirelessRuntimeReady(session: AirPlaySession) {
                central.onWirelessRuntimeReady(session)
                if (controller === expected) listener.onWirelessRuntimeReady(session)
            }
            override fun onWirelessRuntimeUnavailable(session: AirPlaySession) {
                central.onWirelessRuntimeUnavailable(session)
                if (controller === expected) listener.onWirelessRuntimeUnavailable(session)
            }
            override fun onNavigationDataReceived(session: AirPlaySession) {
                central.onNavigationDataReceived(session)
                if (controller === expected) listener.onNavigationDataReceived(session)
            }
            override fun onClusterContentProgress(session: AirPlaySession, progress: ClusterContentProgress) {
                central.onClusterContentProgress(session, progress)
                if (controller === expected) listener.onClusterContentProgress(session, progress)
            }
            override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
                central.onCommand(session, type, params)
                if (controller === expected) listener.onCommand(session, type, params)
            }
            override fun onHostUiRequested(session: AirPlaySession) {
                central.onHostUiRequested(session)
                if (controller === expected) listener.onHostUiRequested(session)
            }
            override fun onDebugLog(message: String) { listener.onDebugLog(message) }
        }
        expected.attachUi(combined) { status ->
            if (controller === expected) {
                statusReporter(status)
                backgroundStatus(expected)(status)
            }
        }
        expected.replayActiveSession(combined)
    }
    private fun notifyUi() {
        main.post { synchronized(this) { ignitionReady?.invoke() } }
    }
    @Synchronized fun isOwner(candidate: Any): Boolean = owner === candidate
    @Synchronized fun hasSession(): Boolean = controller != null || stopAction != null || stopping || retiring.isNotEmpty()
    @Synchronized fun snapshot(): Snapshot? {
        return Snapshot(controller ?: return null, sink ?: return null, width, height, display ?: return null)
    }
    data class Snapshot(val controller: CarPlayController, val sink: AndroidMediaSink, val width: Int,
                        val height: Int, val display: CarPlaySessionDisplay)
    @Synchronized fun store(
        controller: CarPlayController, sink: AndroidMediaSink, width: Int, height: Int, owner: Any,
        display: CarPlaySessionDisplay, diagnosticLog: SessionLogFile? = null, stop: (() -> Unit) -> Unit,
    ) {
        explicitRequestPending = false
        activeSession = null
        updateReadiness(ChannelReadiness())
        this.controller = controller; this.sink = sink
        this.width = width; this.height = height; this.owner = owner; this.display = display
        val arrivalProbe = !explicitUse && phoneState == SelectedPhoneState.DISCONNECTED && !automaticBootstrapAttempted
        if (!explicitUse && configuration?.runtime?.transport == CarPlayTransport.WIRELESS && !automaticBootstrapAttempted) {
            automaticBootstrapAttempted = true
            persistIntent()
        }
        stopWifiReadinessWatch()
        // The one arrival probe has transport/P2P deadlines. An absent phone must not
        // consume the normal connection budget before a genuine arrival event.
        if (configuration?.runtime?.transport == CarPlayTransport.WIRELESS && !arrivalProbe) armStartupDeadline()
        stopAction = stop
        uiDiagnosticLog = diagnosticLog
        refreshService()
        notifyUi()
    }
    @Synchronized fun clear(expected: CarPlayController? = null, keepOwner: Boolean = false) {
        if (expected != null && controller !== expected) return
        controller = null; sink = null; display = null; width = 0; height = 0; active = false; activeSession = null
        updateReadiness(ChannelReadiness())
        if (!keepOwner) {
            stopAction = null; owner = null; uiDiagnosticLog = null
        }
        notifyUi()
    }

    /** A timeout is diagnostic only. Only successful resource completion removes this barrier. */
    @Synchronized fun retire(old: CarPlayController?, media: AndroidMediaSink?, reason: String, completion: () -> Unit = {}) {
        val existing = retiring.firstOrNull { it.controller === old && it.sink === media }
        if (existing != null) { existing.completions.add(completion); return }
        if (old == null && media == null) {
            if (retiring.isEmpty()) main.post { completion(); reconcile() }
            else waitForRetirements(completion)
            return
        }
        CarPlayMediaKeys.detach(old)
        val retirement = Retirement(old, media, reason)
        retirement.completions.add(completion)
        retiring.add(retirement)
        refreshService()
        closeAttempt(retirement)
    }
    private fun waitForRetirements(completion: () -> Unit) {
        retirementWaiters.add(completion)
    }
    @Synchronized private fun mediaClosed(item: Retirement) {
        if (item !in retiring) return
        item.mediaCloseCompleted = true
        item.sinkReleased = true
        if (!item.attemptRunning) closeAttempt(item)
    }
    @Synchronized private fun closeAttempt(item: Retirement) {
        if (item !in retiring || item.attemptRunning) return
        item.attemptRunning = true
        val attempt = ++item.attempt
        closer.execute {
            var mediaFailure: String? = null
            if (!item.sinkReleased) {
                // Release music/audio focus before slow Bonjour/hotspot cleanup.
                val result = item.closes.run("audio", 4_000) { item.sink?.close() }
                item.sinkReleased = result.released || item.mediaCloseCompleted
                mediaFailure = result.failure
            }
            try {
                val current = item.controller
                if (current == null) main.post { finishAttempt(item, attempt, true, emptyList(), mediaFailure) }
                else {
                    val requested = item.closes.run("controller-admission", 4_000) { current.close() }
                    if (!requested.released) {
                        main.post { finishAttempt(item, attempt, false, listOfNotNull(requested.failure), mediaFailure) }
                    } else {
                        val observe = synchronized(this) {
                            if (item.controllerObservationPending) false
                            else { item.controllerObservationPending = true; true }
                        }
                        if (observe) try {
                            current.whenClosed { result ->
                                // Watchdog retries may still be waiting on this exact real close.
                                // Keep only one observer until it actually completes, even when
                                // the attempt that first subscribed has already timed out.
                                main.post { synchronized(this) {
                                    if (item !in retiring) return@post
                                    item.controllerObservationPending = false
                                    // This is the actual close owned by this retirement. Its
                                    // completion remains relevant after a watchdog retry; do
                                    // not wait another deadline just because its observer was
                                    // first registered by an older attempt.
                                    if (!item.attemptRunning) item.attemptRunning = true
                                    finishAttempt(item, item.attempt, result.resourcesReleased, result.failures, mediaFailure)
                                } }
                            }
                        } catch (error: Exception) {
                            synchronized(this) { item.controllerObservationPending = false }
                            throw error
                        }
                    }
                }
            } catch (error: Exception) {
                main.post { finishAttempt(item, attempt, false, listOf(error.javaClass.simpleName), mediaFailure) }
            }
        }
        main.postDelayed({ synchronized(this) {
            if (item in retiring && item.attemptRunning && item.attempt == attempt) {
                item.controller?.cancelCloseWaits()
                item.closes.interruptPending()
                finishAttempt(item, attempt, false, listOf("cleanup deadline; cancellation requested"), null)
            }
        } }, 12_000)
    }
    @Synchronized private fun finishAttempt(item: Retirement, attempt: Int, released: Boolean, failures: List<String>, mediaFailure: String?) {
        if (item !in retiring || item.attempt != attempt || !item.attemptRunning) return
        item.attemptRunning = false
        if (!released || !item.sinkReleased) {
            item.quarantined = item.attempt >= 3
            recordUiDiagnostic("close incomplete reason=${item.reason} resources=$failures media=$mediaFailure; retry same owner")
            // Keep the actual handles and block a second stack. Slow polling after escalation
            // still lets a late native/Binder completion recover without restarting the app.
            main.postDelayed({ closeAttempt(item) }, if (item.quarantined) 10_000 else 1_000)
            refreshService()
            notifyUi()
            return
        }
        retiring.remove(item)
        recordUiDiagnostic("close completed reason=${item.reason} remaining=${retiring.size}")
        item.completions.forEach { it() }
        if (retiring.isEmpty()) {
            val completed = retirementWaiters.toList()
            retirementWaiters.clear()
            completed.forEach { it() }
        }
        refreshService()
        notifyUi()
        reconcile()
    }
    @Synchronized fun stop(completion: () -> Unit = {}) {
        desired = false
        explicitRequestPending = false
        explicitUse = false
        manualDisconnect = true
        stopWifiReadinessWatch()
        recoveryGeneration++
        retryPending = false
        pendingReuse = null
        persistIntent()
        cancelAutomaticEntry("manual-disconnect")
        AutunnNavigationOutputs.endNow()
        stopWaiters.add(completion)
        if (stopping) return
        stopping = true
        val action = stopAction
        if (action != null) action { finishStop() } else stopBackground { finishStop() }
    }
    @Synchronized private fun stopBackground(done: () -> Unit) {
        val old = snapshot()
        clear()
        retire(old?.controller, old?.sink, "user-stop", done)
    }
    @Synchronized private fun finishStop() {
        if (retiring.isNotEmpty()) { waitForRetirements(::finishStop); return }
        if (durableIntentRevision < intentRevision) return
        stopping = false
        val callbacks = stopWaiters.toList()
        stopWaiters.clear()
        callbacks.forEach { it() }
        refreshService()
        reconcile()
    }
    @Synchronized fun terminate(completion: () -> Unit = {}) {
        terminated = true
        startupOwner = null
        foregroundStartupReady = true
        configuration = null
        configuredStartup = null
        ignitionMonitor?.close(); ignitionMonitor = null
        phoneMonitor?.close(); phoneMonitor = null
        phoneState = SelectedPhoneState.UNKNOWN
        automaticBootstrapAttempted = false
        ignitionOwner = null; ignitionListener = null; ignitionReady = null
        ignitionState = null; ignitionOff = false
        ignitionDebouncer = IgnitionStateDebouncer()
        vehicleCandidateRecheck?.let(main::removeCallbacks)
        vehicleCandidateRecheck = null
        ignitionSampleLogged = false; lastIgnitionSample = null
        driverDoorSequence.reset()
        stop {
            refreshService()
            completion()
        }
    }
    @Synchronized fun attachService(value: DiPlaySessionService) { initialize(value); service = value }
    @Synchronized fun resumeService(context: Context) {
        initialize(context)
        if (terminated) return
        val firstResume = !serviceResumeInitialized
        serviceResumeInitialized = true
        if (controller == null && !explicitRequestPending && !explicitUse && !DiPlayPreferences.autoConnect(context)) {
            desired = false
            cancelAutomaticEntry("automatic-connection-disabled")
            persistIntent()
        }
        if (configuration == null && !configurationLoading) {
            configurationLoading = true
            val revision = configurationRevision
            val loadStarted = SystemClock.elapsedRealtime()
            recordUiDiagnostic("stage=configuration-load started=true")
            stateIo.execute {
                val result = runCatching { CarPlayConfigurationStore.load(context) }
                completeOnMain { synchronized(this) {
                    configurationLoading = false
                    recordUiDiagnostic("stage=configuration-load elapsedMs=${SystemClock.elapsedRealtime() - loadStarted} " +
                        "success=${result.isSuccess} current=${revision == configurationRevision}")
                    if (revision == configurationRevision && configuration == null && !terminated) {
                        result.fold(onSuccess = {
                            configuration = it
                            configuredStartup = it?.let { value -> CarPlayConfigurationStore.StartupConfiguration(value) }
                            backgroundSetupRequired = if (it == null ||
                                it.runtime.transport == CarPlayTransport.WIRELESS &&
                                it.runtime.wirelessBluetoothDeviceAddress.isNullOrBlank()) "configuration" else null
                        }, onFailure = {
                            backgroundSetupRequired = "configuration"
                            preparationFailure = "CarPlay准备失败，请返回主页重试；原身份和配对资料已保留。"
                            recordUiDiagnostic("saved configuration unavailable=${it.javaClass.simpleName}")
                        })
                    }
                    refreshPhonePresence()
                    reconcile()
                    refreshService()
                } }
            }
        }
        ensureVehicleMonitor()
        refreshPhonePresence()
        // Repeated service starts (including Controller activation) must not undo a user's
        // return to the car desktop. A new vehicle use arms its own entry in beginVehicleUse.
        if (firstResume && desired && !manualDisconnect && !ignitionOff && DiPlayPreferences.autoConnect(context))
            requestAutomaticEntry()
        main.post { reconcile() }
    }
    @Synchronized fun detachService(value: DiPlaySessionService) {
        if (service !== value) return
        service = null
        // The replacement Service resumes the same absolute deadline or backoff. Receipt
        // and repeated service starts cannot replenish attempts or extend its timeout.
    }
    @Synchronized fun serviceMode(): String = when {
        retiring.any { it.quarantined } -> "cleanup-blocked"
        retiring.isNotEmpty() || stopping -> "closing"
        controller != null -> if (active) "connected" else "connecting"
        preparationFailure != null -> "failed"
        !terminated && (configuration != null || ignitionMonitor != null ||
            automaticEntryRequested && backgroundSetupRequired != null) -> "waiting"
        else -> "stopped"
    }
    private fun refreshService() { service?.refreshSessionState() }
    @Synchronized fun refreshForegroundService() { refreshService() }

    @Synchronized private fun reconcile() {
        if (backgroundSetupRequired != "wifi" || !canKeepSession() || retryStopped || controller != null)
            stopWifiReadinessWatch()
        preparedResume?.let { it(); return }
        if (retryPending) {
            if (SystemClock.elapsedRealtime() >= retryDueAtMillis) continueRetry(recoveryGeneration)
            return
        }
        refreshService()
        val gate = when {
            controller != null -> "controller-present"
            starting -> "preparing"
            startBlockReason() != null -> startBlockReason()
            configurationLoading -> "configuration-loading"
            retryPending -> "retry-wait"
            startupOwner != null && !foregroundStartupReady -> "foreground-preparation"
            configuration == null || backgroundSetupRequired == "configuration" -> "configuration"
            waitingForPhone() -> "selected-phone"
            else -> "ready"
        }
        if (gate != lastStartupGate) {
            lastStartupGate = gate
            recordUiDiagnostic("stage=startup-gate reason=$gate")
        }
        if (controller != null || !canStart() || configurationLoading || retryPending || retryStopped) return
        if (startupOwner != null && !foregroundStartupReady) return
        val context = app ?: return
        val previous = configuration ?: return
        if (backgroundSetupRequired == "configuration" || waitingForPhone()) return
        // UNKNOWN keeps the existing compatibility path on stacks that cannot expose the
        // automotive Bluetooth profile. It cannot mean that a disconnected phone is present.
        // Keep the negotiated viewport, but never carry a previous use's phone/network
        // settings into a newly created Controller.
        starting = true
        // A fresh connection deadline starts in store(), when a stack can actually connect.
        // Keep an existing attempt's absolute deadline across prerequisites and replacement.
        if (previous.runtime.transport == CarPlayTransport.WIRELESS && retryBudget.deadlineMillis != null)
            armStartupDeadline()
        val generation = recoveryGeneration
        val cycle = useGeneration
        val budget = retryBudget
        val configRevision = configurationRevision
        val log = powerLog
        val prepareStarted = SystemClock.elapsedRealtime()
        recordUiDiagnostic("stage=stack-preparation started=true generation=$generation")
        stateIo.execute {
          var created: CarPlaySessionFactory.Created? = null
          var fresh: CarPlayResumeConfiguration? = null
          var failure: Exception? = null
          try {
            val saved = CarPlayConfigurationStore.load(context, previous.runtime.mfiTarget)
                ?.copy(audioCaptureDirectory = previous.audioCaptureDirectory)
                ?: throw IllegalStateException("saved display unavailable")
            if (!hasPermissions(context, saved)) {
                completeOnMain { requireBackgroundSetup(generation, configRevision, "permissions") }
                return@execute
            }
            if (saved.runtime.transport == CarPlayTransport.WIRELESS &&
                WifiRadioStartup.requestEnable(context) == WifiRadioStartup.USER_ACTION) {
                completeOnMain { requireBackgroundSetup(generation, configRevision, "wifi") }
                return@execute
            }
            val prepared = saved.copy(microphoneEnabled = saved.microphoneEnabled &&
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            fresh = prepared
            lateinit var next: CarPlayController
            val built = CarPlaySessionFactory.create(context, prepared,
                object : AirPlaySessionListener {
                    override fun onSessionActive(session: AirPlaySession) = backgroundListener(next).onSessionActive(session)
                    override fun onMainScreenReady(session: AirPlaySession) = backgroundListener(next).onMainScreenReady(session)
                    override fun onMainScreenVideoReceived(session: AirPlaySession) = backgroundListener(next).onMainScreenVideoReceived(session)
                    override fun onSessionEnded(session: AirPlaySession) = backgroundListener(next).onSessionEnded(session)
                    override fun onSessionDisconnected(session: AirPlaySession) = backgroundListener(next).onSessionDisconnected(session)
                    override fun onTransportError(message: String) = backgroundListener(next).onTransportError(message)
                    override fun onVideoFrameRendered(session: AirPlaySession) = backgroundListener(next).onVideoFrameRendered(session)
                    override fun onVideoFrameRendered(session: AirPlaySession, screenType: Int) {
                        backgroundListener(next).onVideoFrameRendered(session, screenType)
                    }
                    override fun onWirelessRuntimeReady(session: AirPlaySession) = backgroundListener(next).onWirelessRuntimeReady(session)
                    override fun onWirelessRuntimeUnavailable(session: AirPlaySession) = backgroundListener(next).onWirelessRuntimeUnavailable(session)
                    override fun onNavigationDataReceived(session: AirPlaySession) = backgroundListener(next).onNavigationDataReceived(session)
                    override fun onClusterContentProgress(session: AirPlaySession, progress: ClusterContentProgress) = backgroundListener(next).onClusterContentProgress(session, progress)
                    override fun onHostUiRequested(session: AirPlaySession) = backgroundListener(next).onHostUiRequested(session)
                    override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) =
                        backgroundListener(next).onCommand(session, type, params)
                    override fun onDebugLog(message: String) { AsyncDiagnosticLog.append(powerLog, message) }
                }, { status -> backgroundStatus(next)(status) }, { _, _ -> }, log,
                addressRecovery = budget.addressRecovery,
                onAddressRecoveryChanged = {
                    // Controller invokes this inside transport callbacks: never take the
                    // background monitor synchronously or persist an obsolete use.
                    main.post { synchronized(this) {
                        if (cycle == useGeneration && retryBudget === budget) persistIntent()
                    } }
                },
                connectionCycle = cycle)
            created = built
            next = built.controller
          } catch (error: Exception) { failure = error }
          val built = created
          val loaded = fresh
          val error = failure
          completeOnMain { synchronized(this) {
            recordUiDiagnostic("stage=stack-preparation elapsedMs=${SystemClock.elapsedRealtime() - prepareStarted} " +
                "success=${built != null} generation=$generation")
            preparedResume = resume@{
              synchronized(this) {
                val obsoleteReason = when {
                    generation != recoveryGeneration -> "generation"
                    configRevision != configurationRevision -> "configuration"
                    controller != null -> "controller-present"
                    !canKeepSession() -> "connection-intent"
                    waitingForPhone() -> "waiting-phone"
                    else -> null
                }
                // Keep valid constructed resources while same-use intent is being saved or an
                // unconfirmed OFF sample is checked. Those completion events call reconcile.
                if (obsoleteReason == null && (ignitionDebouncer.offCandidatePending() ||
                        durableIntentRevision < intentRevision)) return@resume
                preparedResume = null
                starting = false
                if (obsoleteReason != null) {
                    recordUiDiagnostic("background resume discarded reason=$obsoleteReason " +
                        "generation=$generation currentGeneration=$recoveryGeneration " +
                        "configuration=$configRevision currentConfiguration=$configurationRevision " +
                        "durableIntent=$durableIntentRevision intent=$intentRevision")
                    built?.let { retire(it.controller, it.sink, "obsolete-resume") {
                        // A stop can find no stored controller while this stack is in flight.
                        // Its empty-snapshot completion may have waited for durable intent;
                        // finish only after these newly retired resources really release too.
                        if (stopping) finishStop()
                    } }
                    reconcile()
                    return@resume
                }
                if (error != null || built == null || loaded == null) {
                    recordUiDiagnostic("background resume failed=${error?.javaClass?.simpleName ?: "no-stack"}")
                    preparationFailure = "CarPlay准备失败，请返回主页重试；身份资料异常时请恢复原备份，原数据不会被清除。"
                    notifyUi()
                    refreshService()
                    return@resume
                }
                configuration = loaded
                backgroundSetupRequired = null
                configuredStartup = CarPlayConfigurationStore.StartupConfiguration(loaded)
                val next = built.controller
                try {
                    store(next, built.sink, loaded.width, loaded.height, backgroundOwner, loaded.display, log) { stopBackground(it) }
                    CarPlayMediaKeys.attach(context, next, built.sink::selectMediaAudioSource)
                    HongqiWheelControls.attachDiagnosticLog(next, log)
                    if (loaded.airPlay.videoInCar) CarPlayVideo.attach(context, next)
                    context.startForegroundService(Intent(context, DiPlaySessionService::class.java))
                    recordUiDiagnostic("background resume starting controller generation=$generation")
                    next.start()
                } catch (activation: Exception) {
                    clear(next)
                    retire(next, built.sink, "resume-start-failed")
                    preparationFailure = "CarPlay准备失败，请返回主页重试。"
                    notifyUi()
                    recordUiDiagnostic("background activation failed=${activation.javaClass.simpleName}")
                }
                refreshService()
              }
            }
            preparedResume?.invoke()
          } }
        }
    }
    @Synchronized private fun requireBackgroundSetup(generation: Int, revision: Long, reason: String) {
        starting = false
        if (generation != recoveryGeneration || revision != configurationRevision || !canKeepSession()) {
            reconcile()
            return
        }
        backgroundSetupRequired = reason
        if (reason == "wifi") watchWifiReadiness() else stopWifiReadinessWatch()
        recordUiDiagnostic("background resume needs user setup reason=$reason")
        refreshService()
        notifyUi()
    }

    /** Observe radio readiness even before a Controller/P2P manager exists. */
    private fun watchWifiReadiness() {
        if (wifiReadinessReceiver != null || !canKeepSession() || retryStopped) return
        val context = app ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val ready = when (intent.action) {
                    WifiManager.WIFI_STATE_CHANGED_ACTION -> intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1) == WifiManager.WIFI_STATE_ENABLED
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    else -> false
                }
                if (!ready) return
                val expected = this
                main.post { synchronized(this@CarPlayBackgroundSession) {
                    if (wifiReadinessReceiver !== expected || backgroundSetupRequired != "wifi" ||
                        !canKeepSession() || retryStopped) return@synchronized
                    // A broadcast is only a hint. Preparation rechecks permissions and the
                    // actual radio state, with all intent, generation and cleanup gates intact.
                    recordUiDiagnostic("Wi-Fi readiness changed; rechecking background startup")
                    reconcile()
                } }
            }
        }
        try {
            ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            }, null, main, ContextCompat.RECEIVER_EXPORTED)
            wifiReadinessReceiver = receiver
            // Close the race where Wi-Fi enabled between the failed check and registration.
            val ready = runCatching { context.getSystemService(WifiManager::class.java)?.isWifiEnabled == true }
                .getOrDefault(false)
            if (ready) receiver.onReceive(context, Intent(WifiManager.WIFI_STATE_CHANGED_ACTION)
                .putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_ENABLED))
        } catch (error: RuntimeException) {
            recordUiDiagnostic("Wi-Fi readiness receiver unavailable=${error.javaClass.simpleName}")
        }
    }

    private fun stopWifiReadinessWatch() {
        val previous = wifiReadinessReceiver ?: return
        wifiReadinessReceiver = null
        try { app?.unregisterReceiver(previous) } catch (_: IllegalArgumentException) { }
    }

    private fun backgroundListener(expected: CarPlayController): AirPlaySessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            synchronized(this@CarPlayBackgroundSession) {
                if (controller !== expected || !canKeepSession() || session.isClosed() ||
                    expected.currentActiveAirPlaySession() !== session) return
                if (activeSession !== session || retryPending) {
                    // Capture identity before dispatch: a rapid end can clear the Controller's
                    // active getter while the UI thread still has this activation queued.
                    recoveryGeneration++
                    retryPending = false
                    pendingReuse = null
                    retryBudget.disconnected()
                }
                if (activeSession !== session) {
                    updateReadiness(ChannelReadiness())
                    screenOwnerSession = session
                    observedScreenOwner = null
                    // Request the desired screen before waiting for its owner acknowledgement.
                    if (automaticEntryRequested) expected.selectProjectionScreen(true)
                }
                activeSession = session
            }
            main.post { synchronized(this@CarPlayBackgroundSession) {
                if (controller !== expected || activeSession !== session || !canKeepSession() ||
                    expected.currentActiveAirPlaySession() !== session) return@post
                active = true
                updateReadiness(readiness.withCluster(session.clusterContentProgress()).copy(airPlayConnected = true))
                if (session.hasReceivedMainScreenVideo()) completeStartupProgress()
                recordUiDiagnostic("background AirPlay session active")
                notifyAutomaticEntryObserver()
                refreshService()
                notifyUi()
            } }
        }
        override fun onMainScreenVideoReceived(session: AirPlaySession) { main.post { synchronized(this@CarPlayBackgroundSession) {
            if (acceptCurrentProgress(expected, session) && session.hasReceivedMainScreenVideo()) completeStartupProgress()
        } } }
        override fun onMainScreenReady(session: AirPlaySession) { main.post { synchronized(this@CarPlayBackgroundSession) {
            if (!acceptCurrentProgress(expected, session)) return@post
            if (session.hasReceivedMainScreenVideo()) completeStartupProgress()
            notifyAutomaticEntryObserver()
        } } }
        override fun onSessionDisconnected(session: AirPlaySession) { main.post { synchronized(this@CarPlayBackgroundSession) {
            if (!isCurrentSessionEvent(expected, session)) return@post
            active = false; updateReadiness(ChannelReadiness()); retryBudget.disconnected(); refreshService()
        } } }
        override fun onSessionEnded(session: AirPlaySession) { main.post { synchronized(this@CarPlayBackgroundSession) {
            if (isCurrentSessionEvent(expected, session)) backgroundLost(expected, "session-ended")
        } } }
        override fun onTransportError(message: String) { main.post { backgroundLost(expected, "transport-error") } }
        override fun onVideoFrameRendered(session: AirPlaySession) = onVideoFrameRendered(session, 110)
        override fun onVideoFrameRendered(session: AirPlaySession, screenType: Int) { main.post { synchronized(this@CarPlayBackgroundSession) {
            if (!acceptCurrentProgress(expected, session) || screenType != 110) return@post
            completeStartupProgress()
            updateReadiness(readiness.copy(mainFrameRendered = true))
            if (retryBudget.firstFrame(session, SystemClock.elapsedRealtime()))
                main.postDelayed({ checkStableSession(expected, session) }, WirelessStartupPolicy.STABLE_SESSION_MILLIS)
        } } }
        override fun onWirelessRuntimeReady(session: AirPlaySession) { main.post { synchronized(this@CarPlayBackgroundSession) {
            if (acceptCurrentProgress(expected, session)) updateReadiness(readiness.copy(wirelessRuntimeAuthenticated = true))
        } } }
        override fun onWirelessRuntimeUnavailable(session: AirPlaySession) { main.post { synchronized(this@CarPlayBackgroundSession) {
            if (acceptCurrentProgress(expected, session)) updateReadiness(readiness.copy(
                wirelessRuntimeAuthenticated = false, navigationDataReceived = false))
        } } }
        override fun onNavigationDataReceived(session: AirPlaySession) { main.post { synchronized(this@CarPlayBackgroundSession) {
            if (acceptCurrentProgress(expected, session)) updateReadiness(readiness.copy(navigationDataReceived = true))
        } } }
        override fun onClusterContentProgress(session: AirPlaySession, progress: ClusterContentProgress) { main.post { synchronized(this@CarPlayBackgroundSession) {
            if (!acceptCurrentProgress(expected, session)) return@post
            updateReadiness(readiness.withCluster(progress))
        } } }
        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            if (type != "modesChanged" || params["resources"] !is List<*>) return
            val screen = session.resourceModes.state.screen ?: return
            main.post { synchronized(this@CarPlayBackgroundSession) {
                if (!acceptCurrentProgress(expected, session)) return@post
                if (screenOwnerSession !== session) { screenOwnerSession = session; observedScreenOwner = null }
                val previous = observedScreenOwner
                if (screen == previous) return@post
                observedScreenOwner = screen
                if (screen == com.shilapi.xcertplay.airplay.AirPlayResourceModes.Entity.IPHONE)
                    requestAutomaticEntry()
                else if (previous != null) cancelAutomaticEntry("vehicle-owns-screen")
                notifyAutomaticEntryObserver()
            } }
        }
        override fun onHostUiRequested(session: AirPlaySession) = synchronized(this@CarPlayBackgroundSession) {
            if (acceptCurrentProgress(expected, session)) cancelAutomaticEntry("phone-requested-car-ui")
        }
        override fun onDebugLog(message: String) { AsyncDiagnosticLog.append(powerLog, message) }
    }
    private fun acceptCurrentProgress(expected: CarPlayController, session: AirPlaySession): Boolean =
        controller === expected && activeSession === session && expected.currentActiveAirPlaySession() === session &&
            canKeepSession() && !session.isClosed()
    @Synchronized private fun checkStableSession(expected: CarPlayController, session: AirPlaySession) {
        if (controller !== expected || activeSession !== session ||
            expected.currentActiveAirPlaySession() !== session || !canKeepSession() || !active ||
            session.isClosed() || retryBudget.retries == 0) return
        if (session.hasRecentMediaProgress(10_000L) && expected.activeAirPlaySessionToken() != null &&
            retryBudget.resetIfStable(session, SystemClock.elapsedRealtime())) persistIntent()
        else main.postDelayed({ checkStableSession(expected, session) }, 10_000L)
    }
    private fun isCurrentSessionEvent(expected: CarPlayController, session: AirPlaySession): Boolean {
        if (controller !== expected || activeSession !== session) return false
        val current = expected.currentActiveAirPlaySession()
        return current == null || current === session
    }
    private fun backgroundStatus(expected: CarPlayController): (CarPlayStatus) -> Unit = { status -> main.post {
        if (controller === expected && status is CarPlayStatus.Failed) {
            if (status.wifiResetRequired || status.startupFailure == WirelessStartupFailure.HOTSPOT_CONFIGURATION) {
                retryStopped = true
                retryRequiresUserAction = true
                recordUiDiagnostic("background connection requires manual hotspot recovery")
                persistIntent()
                val old = snapshot()
                clear(expected)
                retire(old?.controller, old?.sink, "configuration-failed")
            } else requestRecovery(expected, "startup-failed", status.startupFailure, reuseController = true)
        }
    } }
    @Synchronized private fun canKeepSession(): Boolean = desired && !terminated && !ignitionOff && !manualDisconnect
    @Synchronized private fun backgroundLost(expected: CarPlayController, reason: String) {
        requestRecovery(expected, reason, reuseController = true)
    }
    /** Display changes are neither a wireless failure nor a new retry budget. */
    @Synchronized fun requestConfigurationRestart(expected: CarPlayController, reason: String): Boolean {
        if (controller !== expected || !canKeepSession()) return false
        recoveryGeneration++
        retryPending = false
        pendingReuse = null
        val old = snapshot() ?: return false
        clear(expected)
        retire(old.controller, old.sink, reason)
        return true
    }
    @Synchronized fun requestRecovery(expected: CarPlayController, reason: String,
                                     startupFailure: WirelessStartupFailure? = null,
                                     reuseController: Boolean = false): Boolean {
        if (controller !== expected || !canKeepSession() || retryPending || retryStopped) return false
        if (startupFailure == WirelessStartupFailure.HOTSPOT_CONFIGURATION) {
            retryStopped = true
            retryRequiresUserAction = true
            persistIntent()
            notifyUi()
            return false
        }
        active = false
        updateReadiness(ChannelReadiness())
        retryBudget.disconnected()
        if (waitingForPhone()) {
            recoveryGeneration++
            retryPending = false
            pendingReuse = null
            val old = snapshot() ?: return false
            clear(expected)
            retire(old.controller, old.sink, "waiting-phone")
            recordUiDiagnostic("automatic recovery waiting for selected phone; retry budget retained")
            notifyUi()
            return true
        }
        if (configuration?.runtime?.transport == CarPlayTransport.WIRELESS) armStartupDeadline()
        val canReuse = reuseController && !expected.requiresFullWirelessRebuild(configuration?.runtime)
        pendingReuse = if (canReuse) expected else null
        scheduleRetry(reason)
        if (retryStopped || !canReuse) {
            val old = snapshot() ?: return false
            clear(expected)
            retire(old.controller, old.sink, reason)
        }
        notifyUi()
        return !retryStopped
    }
    /** One boot-local deadline survives stack replacement and backoff within this intent. */
    private fun armStartupDeadline() {
        val budget = retryBudget
        val fresh = budget.deadlineMillis == null
        val due = budget.begin(SystemClock.elapsedRealtime())
        if (startupDeadlineAt != due || startupDeadlineCheck == null) {
            startupDeadlineCheck?.let(main::removeCallbacks)
            startupDeadlineAt = due
            val check = Runnable { synchronized(this) {
                if (retryBudget !== budget || budget.deadlineMillis != due || !canKeepSession()) return@synchronized
                startupDeadlineCheck = null
                startupDeadlineAt = null
                retryStopped = true
                retryPending = false
                pendingReuse = null
                recoveryGeneration++
                recordUiDiagnostic("wireless startup total budget exhausted deadlineMs=$due")
                val old = snapshot()
                clear()
                retire(old?.controller, old?.sink, "startup-total-timeout")
                persistIntent()
                reconcile()
                refreshService()
                notifyUi()
            } }
            startupDeadlineCheck = check
            main.postDelayed(check, (due - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        }
        if (fresh) persistIntent()
    }
    private fun completeStartupProgress() {
        if (retryBudget.deadlineMillis == null) return
        retryBudget.connected()
        startupDeadlineCheck?.let(main::removeCallbacks)
        startupDeadlineCheck = null
        startupDeadlineAt = null
        recordUiDiagnostic("wireless startup complete proof=current-main-video")
        persistIntent()
    }
    @Synchronized private fun scheduleRetry(reason: String) {
        if (!canKeepSession() || retryPending) return
        val delay = retryBudget.nextDelayMillis() ?: run {
            retryStopped = true; persistIntent()
            recordUiDiagnostic("background retry exhausted reason=$reason"); return
        }
        retryPending = true
        retryDueAtMillis = SystemClock.elapsedRealtime() + delay
        persistIntent()
        val generation = recoveryGeneration
        main.postDelayed({ continueRetry(generation) }, delay)
        recordUiDiagnostic("background retry reason=$reason delayMs=$delay")
    }
    @Synchronized private fun continueRetry(generation: Int) {
            if (generation != recoveryGeneration || !retryPending) return
            if (SystemClock.elapsedRealtime() < retryDueAtMillis) return
            if (ignitionDebouncer.offCandidatePending()) {
                // Keep this attempt's budget/owner. Candidate cancellation or expiry resumes
                // it; confirmed parking revokes its generation instead of rebuilding Wi-Fi.
                return
            }
            if (durableIntentRevision < intentRevision) {
                return
            }
            retryPending = false
            val reuse = pendingReuse
            pendingReuse = null
            if (!canKeepSession() || retryStopped) return
            if (waitingForPhone()) {
                if (reuse != null && controller === reuse) {
                    val old = snapshot()
                    clear(reuse)
                    retire(old?.controller, old?.sink, "waiting-phone")
                }
                return
            }
            if (reuse != null && controller === reuse) {
                val reused = try { reuse.tryReconnectWireless(configuration?.runtime) }
                    catch (error: Exception) {
                        recordUiDiagnostic("wireless reuse unavailable=${error.javaClass.simpleName}"); false
                    }
                if (reused) { notifyUi(); return }
                val old = snapshot()
                clear(reuse)
                retire(old?.controller, old?.sink, "retry-full-close")
            }
            reconcile()
    }
    private fun hasPermissions(context: Context, saved: CarPlayResumeConfiguration): Boolean {
        val required = when {
            saved.runtime.transport != CarPlayTransport.WIRELESS -> emptyList()
            Build.VERSION.SDK_INT >= 37 -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_LOCAL_NETWORK)
            saved.runtime.wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI -> if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
            Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES)
            Build.VERSION.SDK_INT >= 31 -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            else -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } &&
            (!saved.runtime.locationReportingEnabled || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
}
