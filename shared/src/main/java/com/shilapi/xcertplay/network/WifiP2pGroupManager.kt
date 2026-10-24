package com.shilapi.xcertplay.network

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.HandlerThread
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Creates a Wi-Fi Direct group owner that can also be joined as a legacy AP.
 *
 * Android 10+ prefers 5 GHz and requests a nonpersistent group. Android 9 uses the system's
 * credentials and channel. [close] removes the live group and releases the callback thread.
 */
class WifiP2pGroupManager(
    context: Context,
    private val diagnostic: (String) -> Unit = {},
    private val preferredChannel: Int = WifiP2pChannels.AUTO,
    private val onRuntimeFailure: (IOException) -> Unit = {},
) : WirelessHotspotManager {
    init {
        require(WifiP2pChannels.isValid(preferredChannel)) { "Unsupported Wi-Fi Direct channel: $preferredChannel" }
    }
    private val appContext = context.applicationContext
    private val p2pManager = appContext.getSystemService(WifiP2pManager::class.java)
        ?: throw IllegalStateException("WifiP2pManager is unavailable")
    private val stateLock = Object()
    private val cleanupLock = Any()
    private val random = SecureRandom()
    private val configurationMemory = P2pConfigurationMemory(appContext)
    private var rememberedAttempt: P2pConfigurationMemory.Record? = null
    private var pendingSuccess: (() -> Boolean)? = null
    private var carPlayConfirmed = false
    private val ownership = appContext.getSharedPreferences("carplay_wifi_p2p", Context.MODE_PRIVATE)
    private val ownerToken = java.util.UUID.randomUUID().toString()
    private var ownershipClaimed = false
    private val scopedDeviceId by lazy {
        // ANDROID_ID is scoped to the signing key, user and device on our supported OS versions.
        runCatching { Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: ownership.getString("fallback_id", null)
            ?: java.util.UUID.randomUUID().toString().also {
                check(ownership.edit().putString("fallback_id", it).commit())
            }
    }
    private val ssidPrefix by lazy { P2pOwnership.prefix(appContext.packageName, scopedDeviceId) }
    private val legacySsidPrefix by lazy { P2pOwnership.legacyPrefix(appContext.packageName, scopedDeviceId) }
    private val previousSsidPrefix by lazy { P2pOwnership.previousPrefix(appContext.packageName, scopedDeviceId) }

    private var channel: WifiP2pManager.Channel? = null
    private var callbackThread: HandlerThread? = null
    private var channelEpoch = 0
    private var channelRecovering = false
    private var channelRecoveryUsed = false
    private var channelFailure: IOException? = null
    private var runtimeFailureReported = false
    private var startupDeadlineNanos = Long.MAX_VALUE
    private var observedP2pState = -1
    private var created = false
    private var closed = false
    private var startAttempt: StartAttempt? = null
    @Volatile private var observedCreatedName: String? = null
    @Volatile private var requestedName: String? = null
    private var activeInfo: WirelessHotspotInfo? = null
    private var continuityRegistered = false
    @Volatile private var continuityLost = false
    private val continuityReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            // State broadcasts are readiness hints, never proof that createGroup will succeed.
            if (intent.action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) synchronized(stateLock) {
                observedP2pState = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                stateLock.notifyAll()
            }
            // Broadcasts can only revoke reuse, never prove ownership or authorize a group.
            val expected = observedCreatedName ?: return
            if (intent.action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION &&
                intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                groupContinuityLost("p2p-disabled")
            }
            if (intent.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                val info = intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                val group = intent.getParcelableExtra<WifiP2pGroup>(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                if (info?.groupFormed == false || group != null && (!group.isGroupOwner || group.networkName != expected))
                    groupContinuityLost("group-lost")
            }
        }
    }

    private fun groupContinuityLost(reason: String) {
        val failure = P2pTransientFailure("Wi-Fi P2P $reason")
        val attempt = synchronized(stateLock) {
            if (closed || continuityLost) return
            continuityLost = true
            channelFailure = failure
            stateLock.notifyAll()
            startAttempt
        }
        diagnostic("Wi-Fi P2P continuity lost reason=$reason")
        if (attempt != null) failAttempt(attempt, failure) else reportRuntimeChannelFailure()
    }

    override fun validateReady() = synchronized(stateLock) {
        if (closed || !created || activeInfo == null || continuityLost || !ownsCurrentAttempt())
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_NOT_READY,
                "Wi-Fi P2P group is no longer available")
        Unit
    }

    private val liveRadio = P2pGroupRadioInfo()

    /** Reuse only this manager's exact live GO; a saved SSID alone is not ownership proof. */
    fun reuseHealthyGroup(timeoutMillis: Long = 1_000): WirelessHotspotInfo? = synchronized(cleanupLock) reuse@{
        val snapshot = synchronized(stateLock) state@{
            if (closed || channelRecovering || channelFailure != null || !created || startAttempt != null || !ownershipClaimed || !ownsCurrentAttempt() ||
                !continuityRegistered || continuityLost)
                return@state null
            val current = channel ?: return@state null
            val previous = activeInfo ?: return@state null
            current to previous
        } ?: return@reuse null
        val (current, previous) = snapshot
        val group = try { cleanupGroupInfo(current, deadlineAfter(timeoutMillis)) }
        catch (error: IOException) {
            diagnostic("Wi-Fi P2P reuse unavailable failureClass=${error.javaClass.simpleName}")
            return@reuse null
        } ?: return@reuse null
        val interfaceName = group.`interface`?.takeIf { it.isNotBlank() } ?: return@reuse null
        val addresses = interfaceAddresses(interfaceName)
        val bssid = interfaceHardwareAddress(interfaceName) ?: group.owner?.deviceAddress?.takeIf { it.isNotBlank() }
        val frequency = liveRadio.frequencyMHz(group, interfaceName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && frequency == null) return@reuse null
        val refreshed = WirelessHotspotInfo(group.networkName.orEmpty(), group.passphrase.orEmpty(), groupSecurity(group),
            frequency?.let(::wifiFrequencyMhzToChannel) ?: 0, frequency, bssid, interfaceName,
            addresses.firstOrNull(), previous.bandLabel, WirelessHotspotBackend.WIFI_P2P, addresses,
            accessPointBssid = HotspotInterfaceBssid.readAccessPoint(interfaceName))
        if (!canReuseOwnedP2pGroup(previous, refreshed, ownsCleanupGroup(group, previous.ssid))) return@reuse null
        synchronized(stateLock) {
            if (closed || !created || channel !== current || !ownsCurrentAttempt() || continuityLost) return@reuse null
            activeInfo = refreshed
        }
        diagnostic("Wi-Fi P2P reused live owned group; credentials retained")
        refreshed
    }

    override fun connectionDiagnosticSnapshot(): String {
        val current = synchronized(stateLock) { if (closed) null else channel }
            ?: return "p2pGroup=unavailable association=unknown"
        val result = AtomicReference<WifiP2pGroup?>()
        val latch = CountDownLatch(1)
        return try {
            p2pManager.requestGroupInfo(current) { result.set(it); latch.countDown() }
            if (!latch.await(500, TimeUnit.MILLISECONDS)) {
                "p2pGroup=callback_timeout association=unknown"
            } else {
                val group = result.get()
                if (group == null) "p2pGroup=absent association=unknown"
                else {
                    val same = group.isGroupOwner && group.networkName == observedCreatedName && ownsCurrentAttempt()
                    val actual = if (same && !group.`interface`.isNullOrBlank())
                        liveRadio.frequencyMHz(group, group.`interface`) else null
                    "p2pGroup=present owner=${group.isGroupOwner} sameGroup=$same " +
                        "reportedP2pClients=${group.clientList.size} association=unknown legacyClientIdentity=framework_snapshot_unverified " +
                        "requestedChannel=$preferredChannel actualMHz=${actual ?: "unknown"} " +
                        "radioSource=${if (same) liveRadio.detail else "different_group"} sampledAtNs=${System.nanoTime()}"
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            "p2pGroup=interrupted association=unknown"
        } catch (error: RuntimeException) {
            "p2pGroup=unavailable failureClass=${error.javaClass.simpleName} association=unknown"
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "WifiP2pGroupManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        if (legacy && preferredChannel != WifiP2pChannels.AUTO && !WifiP2pChannels.legacySelectionSupported()) {
            throw IOException("Android 9 Wi-Fi Direct uses the system-selected channel. Choose Auto and reconnect.")
        }

        val attempt = StartAttempt()
        synchronized(stateLock) {
            check(!closed) { "WifiP2pGroupManager is closed" }
            check(startAttempt == null && !created) {
                "A Wi-Fi P2P group is already starting or active"
            }
            startAttempt = attempt
        }

        val thread = HandlerThread("xcertplay-wifi-p2p").apply { start() }
        attempt.thread = thread
        val deadlineNanos = deadlineAfter(timeoutMillis)
        startupDeadlineNanos = deadlineNanos
        val credentials = randomCredentials()

        try {
            awaitWifiEnabled(attempt, deadlineNanos)
            val station = readStation()
            val stationFrequency = station.alignmentFrequency
            checkPrerequisites(station)
            val remembered = configurationMemory.read()
            val preferred = remembered?.takeIf {
                preferredChannel == WifiP2pChannels.AUTO && it.stationMHz == stationFrequency
            }
            diagnostic("Wi-Fi P2P channel preference=${if (preferredChannel == WifiP2pChannels.AUTO) "auto" else preferredChannel} " +
                "frequencyMHz=${WifiP2pChannels.frequencyMhz(preferredChannel) ?: "auto"}")
            diagnostic(when {
                preferredChannel != WifiP2pChannels.AUTO -> "Wi-Fi P2P remembered skipped=manual_channel"
                preferred != null -> "Wi-Fi P2P remembered first mode=${preferred.request.mode} frequencyMHz=${preferred.request.frequencyMHz ?: "auto"}"
                remembered != null -> "Wi-Fi P2P remembered skipped=station_channel_changed"
                else -> "Wi-Fi P2P remembered unavailable"
            })
            val initialEpoch = synchronized(stateLock) {
                callbackThread = thread
                ++channelEpoch
            }
            val initialChannel = p2pManager.initialize(
                appContext,
                thread.looper,
                createChannelListener(attempt, initialEpoch),
            ) ?: throw P2pTransientFailure("Wi-Fi P2P Channel initialization failed")
            val p2pChannel = try {
                synchronized(stateLock) {
                    if (channelEpoch == initialEpoch && !channelRecovering) {
                        channel = initialChannel
                        attempt.channel = initialChannel
                    }
                    ensureStartActiveLocked(attempt)
                    channel ?: throw P2pTransientFailure("Wi-Fi P2P Channel is unavailable")
                }
            } finally { if (initialChannel !== synchronized(stateLock) { channel }) initialChannel.close() }
            registerContinuityMonitor(attempt, thread)

            if (!legacy) logP2pState(attempt, p2pChannel)
            else diagnostic("Wi-Fi P2P API 28 system credentials and automatic channel; no hotspot fallback")
            // Preferences disappear on reinstall, but the scoped namespace survives.
            val existing = requestGroupInfo(attempt, p2pChannel, REQUEST_POLL_NANOS, requireResponse = true)
            diagnostic("Wi-Fi P2P existingGroup=${existing != null}")
            if (existing != null) {
                val recordedName = ownership.getString("owned_ssid", null)
                val knownGroup = P2pOwnership.canReclaim(existing.isGroupOwner, existing.networkName,
                    recordedName, ssidPrefix, legacySsidPrefix, previousSsidPrefix)
                if (!knownGroup) throw P2pResetRequiredException()
                ensureStartActive(attempt)
                claimOwnership()
                diagnostic("Wi-Fi P2P reclaiming retained owned group")
                removeGroupBlocking(currentChannel(attempt), existing.networkName)
                val removalDeadline = minOf(deadlineNanos, deadlineAfter(REMOVE_GROUP_TIMEOUT_MILLIS))
                while (requestGroupInfo(attempt, p2pChannel, REQUEST_POLL_NANOS, requireResponse = true) != null) {
                    if (remainingNanos(removalDeadline) == 0L) throw IOException("Wi-Fi Direct reset did not finish")
                    synchronized(stateLock) { waitNanos(TimeUnit.MILLISECONDS.toNanos(100)) }
                }
            } else claimOwnership()

            val selectedChannel = WifiP2pChannels.selectExperimentalChannel(preferredChannel, stationFrequency)
            val creation = if (legacy) createLegacyGroup(attempt, deadlineNanos, timeoutMillis, stationFrequency, preferred)
            else P2pStartupRecovery.create(
                stationFrequency = stationFrequency,
                preferred = preferred?.request,
                preferredChannel = selectedChannel,
                beforeRetry = {
                    ensureStartActive(attempt)
                    // Do not cancel discovery, toggle Wi-Fi, or remove a newly observed group.
                    // A competing app may have acquired the radio since our rejected request.
                    synchronized(stateLock) { waitNanos(TimeUnit.MILLISECONDS.toNanos(500)) }
                    ensureStartActive(attempt)
                    checkPrerequisites(readStation())
                    if (requestGroupInfo(attempt, p2pChannel, REQUEST_POLL_NANOS, requireResponse = true) != null) {
                        throw P2pResetRequiredException()
                    }
                },
                request = { selection ->
                    ensureStartActive(attempt)
                    if (remainingNanos(deadlineNanos) == 0L) throw P2pTransientFailure("Wi-Fi Direct startup timed out")
                    val config = if (selection.mode == P2pCreationMode.SYSTEM_DEFAULT) null else {
                        P2pConfigBuildDiagnostics.build(Build.VERSION.SDK_INT, selection, diagnostic) {
                            val builder = WifiP2pConfig.Builder()
                                .setNetworkName(credentials.ssid)
                                .setPassphrase(credentials.passphrase)
                            builder.setGroupOperatingFrequency(requireNotNull(selection.frequencyMHz))
                            builder.build()
                        }
                    }
                    if (config != null && !ownership.edit().putString("owned_ssid", credentials.ssid).commit()) {
                        throw IOException("Could not record Wi-Fi P2P group ownership")
                    }
                    val request = CreateRequest()
                    // Keep the requested SSID from the value we supplied. Some Android 10
                    // vendor frameworks omit WifiP2pConfig.getNetworkName(), even though they
                    // support the API 29 Builder methods used above. Reading config.networkName
                    // therefore throws NoSuchMethodError on those devices.
                    diagnostic("Wi-Fi P2P create mode=${selection.mode} frequencyMHz=${selection.frequencyMHz ?: "auto"}")
                    // The API 29 overload with null config uses system-generated credentials
                    // without requesting a persistent group, unlike the older two-argument API.
                    val usingRemembered = preferred?.request == selection
                    if (usingRemembered) synchronized(stateLock) { rememberedAttempt = preferred }
                    try {
                        submitCreate(attempt, request, if (config == null) null else credentials.ssid) { live, listener ->
                            p2pManager.createGroup(live, config, listener)
                        }
                        awaitGroupCreated(attempt, request, deadlineNanos, timeoutMillis)
                    } catch (failure: P2pCreateRejected) {
                        if (usingRemembered && failure.reason == WifiP2pManager.ERROR && preferred != null) {
                            if (configurationMemory.forget(preferred)) diagnostic("Wi-Fi P2P remembered cleared=create_rejected")
                        }
                        throw failure
                    }
                },
            )
            val group = awaitUsableGroup(
                attempt = attempt,
                channel = p2pChannel,
                credentials = if (legacy || creation.mode == P2pCreationMode.SYSTEM_DEFAULT) null else credentials,
                deadlineNanos = deadlineNanos,
                timeoutMillis = timeoutMillis,
            )
            if (!ownership.edit().putString("owned_ssid", group.ssid).commit()) {
                throw IOException("Could not record Wi-Fi P2P group ownership")
            }
            diagnostic("Wi-Fi P2P ready mode=${creation.mode} band=${group.bandLabel} channel=${group.channel} " +
                "frequencyMHz=${group.frequencyMHz ?: "unknown"} apHint=${group.accessPointBssid != null} atNs=${System.nanoTime()}")
            val matched = when {
                group.frequencyMHz == null -> "unknown"
                creation.frequencyMHz == null -> "system_selected"
                else -> (creation.frequencyMHz == group.frequencyMHz).toString()
            }
            diagnostic("Wi-Fi P2P channel requestedMHz=${creation.frequencyMHz ?: "auto"} " +
                "actualMHz=${group.frequencyMHz ?: "unknown"} matched=$matched radioSource=${liveRadio.detail}")
            if (preferredChannel != WifiP2pChannels.AUTO && preferredChannel != WifiP2pChannels.PREFER_5_GHZ &&
                creation.frequencyMHz != null &&
                group.frequencyMHz != null && group.frequencyMHz != creation.frequencyMHz) {
                throw P2pChannelUnavailableException(preferredChannel,
                    "The car selected channel ${group.channel} instead.")
            }
            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
                created = true
                activeInfo = group
                startAttempt = null
                // Manual experiments must not replace the proven automatic configuration.
                pendingSuccess = if (preferredChannel == WifiP2pChannels.AUTO && (legacy || group.frequencyMHz != null)) {
                    { configurationMemory.remember(creation, group.frequencyMHz, stationFrequency) }
                } else null
            }
            return group
        } catch (failure: Exception) {
            diagnostic("Wi-Fi P2P startup stopped type=${failure.javaClass.simpleName}")
            try { cleanupFailedStart(attempt) }
            catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
                diagnostic("Wi-Fi P2P cleanup retained failure=${cleanupFailure.javaClass.simpleName}")
            }
            if (failure is P2pResetRequiredException) throw failure
            // Only known service/readiness conditions enter the existing finite retry budget.
            // Permission, ownership, settings and unconfirmed cleanup remain visible to the user.
            val reason = if (failure is P2pTransientFailure ||
                failure is P2pCreateRejected && failure.reason == WifiP2pManager.BUSY)
                WirelessStartupFailure.HOTSPOT_NOT_READY else WirelessStartupFailure.HOTSPOT_CONFIGURATION
            throw WirelessStartupException(reason,
                "Wi-Fi Direct startup failed: ${failure.message ?: failure.javaClass.simpleName}")
                .also { it.initCause(failure) }
        }
    }

    override fun onCarPlayConfirmed() = synchronized(stateLock) {
        if (closed || !created || carPlayConfirmed) return@synchronized
        val save = pendingSuccess ?: return@synchronized
        // Storage failure must not interrupt a working CarPlay session.
        carPlayConfirmed = true
        val saved = runCatching { save() }.getOrDefault(false)
        diagnostic("Wi-Fi P2P remembered saved=$saved proof=authenticated_first_frame")
    }

    private fun channelCleanupPending(): Boolean = ownership.getBoolean("channel_limit_pending", false)

    private fun recordChannelCleanup(pending: Boolean) {
        if (!ownership.edit().putBoolean("channel_limit_pending", pending).commit())
            throw IOException("Could not record channel cleanup responsibility")
    }

    private fun setLegacyChannel(channel: WifiP2pManager.Channel, value: Int, done: (Boolean) -> Unit) {
        val method = WifiP2pManager::class.java.getMethod("setWifiP2pChannels", WifiP2pManager.Channel::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, WifiP2pManager.ActionListener::class.java)
        method.invoke(p2pManager, channel, 0, value, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { done(true) }
            override fun onFailure(reason: Int) { diagnostic("channel setter rejected reason=$reason"); done(false) }
        })
    }

    private fun clearLegacyChannel(channel: WifiP2pManager.Channel) {
        if (Build.VERSION.SDK_INT != 28 || !channelCleanupPending()) return
        var groupMatches = false
        val done = CountDownLatch(1)
        try {
            p2pManager.requestGroupInfo(channel) { group ->
                groupMatches = group == null || group.isGroupOwner && group.networkName == observedCreatedName
                done.countDown()
            }
            if (!done.await(500, TimeUnit.MILLISECONDS)) {
                P2pChannelRestriction.release(ownerToken, ::channelCleanupPending, ::recordChannelCleanup,
                    { false }, { value, callback -> setLegacyChannel(channel, value, callback) }, diagnostic)
                return
            }
            P2pChannelRestriction.release(ownerToken, ::channelCleanupPending, ::recordChannelCleanup,
                { ownsCurrentAttempt() && groupMatches },
                { value, callback -> setLegacyChannel(channel, value, callback) }, diagnostic)
        } catch (failure: Exception) {
            diagnostic("channel cleanup pending failure=${failure.javaClass.simpleName}")
            if (failure is InterruptedException) Thread.currentThread().interrupt()
        }
    }

    private fun createLegacyGroup(attempt: StartAttempt, deadlineNanos: Long,
        timeoutMillis: Long, stationFrequency: Int?, remembered: P2pConfigurationMemory.Record?): P2pCreationRequest {
        awaitP2pEnabled(attempt, deadlineNanos)
        configureLegacyDeviceName(attempt, currentChannel(attempt), deadlineNanos)
        val supported = WifiP2pChannels.legacySelectionSupported()
        val fiveGhz = runCatching { appContext.getSystemService(WifiManager::class.java)?.is5GHzBandSupported }
            .getOrNull() != false
        val defaults = P2pStartupRecovery.legacyPlan(stationFrequency, preferredChannel, supported, fiveGhz)
        val preferred = remembered?.request?.takeIf { it in defaults }
        val candidates = if (preferred == null) defaults else listOf(preferred) + defaults.filter { it != preferred }
        if (preferred != null) synchronized(stateLock) { rememberedAttempt = remembered }
        val budget = P2pBusyRecovery(deadlineNanos)
        var lastRejection: P2pCreateRejected? = null
        for ((index, candidate) in candidates.withIndex()) {
            ensureLegacyRetrySafe(attempt)
            val requestedChannel = candidate.frequencyMHz?.let(::wifiFrequencyMhzToChannel) ?: WifiP2pChannels.AUTO
            val effectiveChannel = if (requestedChannel != WifiP2pChannels.AUTO || channelCleanupPending()) {
                P2pChannelRestriction.acquire(ownerToken, requestedChannel, deadlineNanos,
                    ::channelCleanupPending, ::recordChannelCleanup,
                    { ownsCurrentAttempt() && !attempt.stopped && !closed },
                    { value, callback -> setLegacyChannel(immediateChannel(attempt), value, callback) }, diagnostic)
            } else WifiP2pChannels.AUTO
            val manual = preferredChannel != WifiP2pChannels.AUTO && preferredChannel != WifiP2pChannels.PREFER_5_GHZ
            if (manual && effectiveChannel != requestedChannel)
                throw P2pChannelUnavailableException(preferredChannel, "The channel restriction was rejected and cleared.")
            // A failed setter that was safely cleared falls back to system Auto once.
            val effective = if (effectiveChannel == WifiP2pChannels.AUTO)
                P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT) else candidate
            while (true) {
                ensureLegacyRetrySafe(attempt)
                budget.submitted()
                val request = CreateRequest()
                diagnostic("Wi-Fi P2P create mode=${effective.mode} api=28 attempt=${budget.submissions} " +
                    "requestedMHz=${effective.frequencyMHz ?: "auto"} p2pState=$observedP2pState atNs=${System.nanoTime()}")
                try {
                    submitCreate(attempt, request, null) { live, listener -> p2pManager.createGroup(live, listener) }
                    awaitGroupCreated(attempt, request, deadlineNanos, timeoutMillis)
                    return effective
                } catch (failure: P2pCreateRejected) {
                    lastRejection = failure
                    if (failure.reason == WifiP2pManager.P2P_UNSUPPORTED || failure.reason == WifiP2pManager.NO_PERMISSION)
                        throw failure
                    if (failure.reason == WifiP2pManager.BUSY) {
                        val delay = budget.busyDelayNanos()
                            ?: throw P2pTransientFailure("Wi-Fi P2P remained busy within its startup budget", failure)
                        diagnostic("Wi-Fi P2P busy recovery delayMs=${TimeUnit.NANOSECONDS.toMillis(delay)} " +
                            "submission=${budget.submissions} p2pState=$observedP2pState")
                        waitBusyBackoff(attempt, delay, deadlineNanos)
                        continue
                    }
                    if (manual || effective.mode == P2pCreationMode.SYSTEM_DEFAULT || index == candidates.lastIndex)
                        throw failure
                    break // Explicit channel rejection: clear the old restriction before the next candidate.
                }
            }
        }
        throw lastRejection ?: P2pTransientFailure("Wi-Fi P2P startup budget exhausted")
    }

    private fun awaitP2pEnabled(attempt: StartAttempt, deadlineNs: Long) = synchronized(stateLock) {
        // Unknown requires a real API result; a disabled broadcast explicitly withholds readiness.
        while (observedP2pState == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
            ensureStartActiveLocked(attempt)
            val remaining = deadlineNs - System.nanoTime()
            if (remaining <= 0) throw P2pTransientFailure("Wi-Fi P2P service is still starting")
            TimeUnit.NANOSECONDS.timedWait(stateLock, remaining)
        }
    }

    private fun ensureLegacyRetrySafe(attempt: StartAttempt) {
        ensureStartActive(attempt)
        if (synchronized(stateLock) { attempt.request?.completed == false })
            throw P2pTransientFailure("Wi-Fi P2P create result is unresolved; no second request allowed")
        checkPrerequisites(readStation())
        if (requestGroupInfo(attempt, currentChannel(attempt), REQUEST_POLL_NANOS, requireResponse = true) != null)
            throw P2pResetRequiredException()
    }

    private fun waitBusyBackoff(attempt: StartAttempt, delayNanos: Long, deadlineNanos: Long) {
        val wakeAt = minOf(System.nanoTime() + delayNanos, deadlineNanos)
        synchronized(stateLock) {
            while (true) {
                ensureStartActiveLocked(attempt)
                val remaining = remainingNanos(wakeAt)
                if (remaining == 0L) return
                // State hints may wake us early, but cannot bypass the retry pacing or guards.
                waitNanos(remaining)
            }
        }
    }

    private fun configureLegacyDeviceName(attempt: StartAttempt, channel: WifiP2pManager.Channel,
        deadlineNanos: Long) {
        // Android 9 generates DIRECT-xx-<device name>; it has no per-group SSID API.
        val nameDeadline = minOf(deadlineNanos, System.nanoTime() + TimeUnit.SECONDS.toNanos(1))
        var completed = false
        var changed = false
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() = finish(true)
            override fun onFailure(reason: Int) = finish(false)
            private fun finish(success: Boolean) = synchronized(stateLock) {
                completed = true
                changed = success
                stateLock.notifyAll()
            }
        }
        try {
            WifiP2pManager::class.java.getMethod("setDeviceName", WifiP2pManager.Channel::class.java,
                String::class.java, WifiP2pManager.ActionListener::class.java)
                .invoke(p2pManager, channel, P2pOwnership.deviceName(ssidPrefix), listener)
            synchronized(stateLock) {
                while (!completed && remainingNanos(nameDeadline) > 0) {
                    ensureStartActiveLocked(attempt)
                    waitNanos(remainingNanos(nameDeadline))
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (unavailable: ReflectiveOperationException) {
            diagnostic("Wi-Fi P2P branded name unavailable type=${unavailable.javaClass.simpleName}")
        } catch (unavailable: SecurityException) {
            diagnostic("Wi-Fi P2P branded name unavailable type=SecurityException")
        }
        diagnostic("Wi-Fi P2P branded name applied=$changed")
    }

    override fun close() = synchronized(cleanupLock) cleanup@{
        val attempt: StartAttempt?
        val activeChannel: WifiP2pManager.Channel?
        val activeThread: HandlerThread?
        val removeGroup: Boolean
        synchronized(stateLock) {
            if (closed && channel == null && callbackThread == null && startAttempt == null) return@cleanup
            closed = true
            activeInfo = null
            if (!carPlayConfirmed) rememberedAttempt?.let {
                if (configurationMemory.forget(it)) diagnostic("Wi-Fi P2P remembered cleared=session_unconfirmed")
            }
            pendingSuccess = null
            attempt = startAttempt
            attempt?.stopped = true
            stateLock.notifyAll()
            activeChannel = channel ?: attempt?.channel
            activeThread = callbackThread ?: attempt?.thread
            removeGroup = created || attempt?.createSucceeded == true
        }
        unregisterContinuityMonitor()

        if (synchronized(stateLock) { attempt?.request?.completed == false }) {
            throw IOException("Wi-Fi P2P group creation cleanup is awaiting its callback")
        }
        if (removeGroup && activeChannel != null) {
            removeGroupBlocking(activeChannel)
            synchronized(stateLock) { created = false; attempt?.createSucceeded = false }
        }
        if (activeChannel != null) clearLegacyChannel(activeChannel)
        activeChannel?.close()
        synchronized(stateLock) { channel = null; attempt?.channel = null }
        stopCallbackThread(activeThread)
        synchronized(stateLock) {
            callbackThread = null
            attempt?.thread = null
            startAttempt = null
        }
    }

    private fun stopCallbackThread(thread: HandlerThread?) {
        if (thread == null) return
        thread.quitSafely()
        if (Thread.currentThread() === thread) throw IOException("Wi-Fi P2P callback thread cannot join itself")
        try { thread.join(REMOVE_GROUP_TIMEOUT_MILLIS) }
        catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while closing Wi-Fi P2P callback thread", interrupted)
        }
        if (thread.isAlive) throw IOException("Wi-Fi P2P callback thread is still running")
    }

    private fun registerContinuityMonitor(attempt: StartAttempt, thread: HandlerThread) {
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION).apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        while (true) {
            // Recovery also takes cleanupLock. Never wait for it while retaining that lock.
            ensureStartActive(attempt)
            val registered = synchronized(cleanupLock) {
                synchronized(stateLock) state@{
                    if (channelRecovering) return@state false
                    ensureStartActiveLocked(attempt)
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            appContext.registerReceiver(continuityReceiver, filter, null, Handler(thread.looper), Context.RECEIVER_EXPORTED)
                        } else appContext.registerReceiver(continuityReceiver, filter, null, Handler(thread.looper))
                        continuityRegistered = true
                    } catch (error: RuntimeException) {
                        diagnostic("Wi-Fi P2P reuse disabled=lifecycle-monitor-unavailable failureClass=${error.javaClass.simpleName}")
                    }
                    true
                }
            }
            if (registered) return
        }
    }

    private fun unregisterContinuityMonitor() {
        if (!continuityRegistered) return
        appContext.unregisterReceiver(continuityReceiver)
        continuityRegistered = false
    }

    private fun currentChannel(attempt: StartAttempt): WifiP2pManager.Channel = synchronized(stateLock) {
        ensureStartActiveLocked(attempt)
        channel ?: throw P2pTransientFailure("Wi-Fi P2P Channel is unavailable")
    }

    private fun immediateChannel(attempt: StartAttempt): WifiP2pManager.Channel = synchronized(stateLock) {
        // Hidden-setter serialization must never wait for recovery: close may need that same
        // serialization lock while holding cleanupLock, which recovery also needs.
        if (channelRecovering) throw P2pTransientFailure("Wi-Fi P2P Channel changed during channel selection")
        ensureStartActiveLocked(attempt)
        channel ?: throw P2pTransientFailure("Wi-Fi P2P Channel is unavailable")
    }

    private fun createChannelListener(attempt: StartAttempt, epoch: Int): WifiP2pManager.ChannelListener =
        WifiP2pManager.ChannelListener {
            val recover = synchronized(stateLock) {
                if (closed || epoch != channelEpoch) return@ChannelListener
                if (channelRecoveryUsed) {
                    channelFailure = P2pTransientFailure("Wi-Fi P2P Channel disconnected after its recovery allowance")
                    stateLock.notifyAll()
                    false
                } else {
                    channelRecoveryUsed = true
                    channelRecovering = true
                    stateLock.notifyAll()
                    true
                }
            }
            // Never block the callback looper while waiting for initialize/query callbacks.
            Thread({
                if (recover) recoverChannel(attempt, epoch)
                else reportRuntimeChannelFailure()
            }, "autunn-p2p-channel-recovery").apply { isDaemon = true; start() }
        }

    private fun recoverChannel(attempt: StartAttempt, lostEpoch: Int) {
        var oldChannel: WifiP2pManager.Channel? = null
        synchronized(cleanupLock) {
            try {
                val thread = synchronized(stateLock) {
                    if (closed || channelEpoch != lostEpoch) return
                    oldChannel = channel
                    callbackThread ?: throw P2pTransientFailure("Wi-Fi P2P callback thread is unavailable")
                }
                val duringStartup = synchronized(stateLock) { startAttempt === attempt }
                val deadline = minOf(deadlineAfter(CHANNEL_RECOVERY_TIMEOUT_MILLIS),
                    if (duringStartup) startupDeadlineNanos else Long.MAX_VALUE)
                if (remainingNanos(deadline) == 0L) throw P2pTransientFailure("Wi-Fi P2P recovery has no startup time remaining")
                val nextEpoch = synchronized(stateLock) { ++channelEpoch }
                val fresh = p2pManager.initialize(appContext, thread.looper, createChannelListener(attempt, nextEpoch))
                    ?: throw P2pTransientFailure("Wi-Fi P2P Channel reinitialization failed")
                synchronized(stateLock) {
                    if (closed) { fresh.close(); return }
                    channel = fresh
                    attempt.channel = fresh // Cleanup must query/remove through the new, live Channel.
                }
                val group = cleanupGroupInfo(fresh, deadline)
                synchronized(stateLock) {
                    if (closed || channelEpoch != nextEpoch) return
                    channelFailure?.let { throw it }
                    if (ownershipClaimed && !ownsCurrentAttempt())
                        throw IOException("Wi-Fi P2P ownership changed during Channel recovery")
                    if (duringStartup) {
                        // A lost reply does not prove rejection. In particular a system-named
                        // API 28 group cannot identify an unacknowledged create, so keep its barrier.
                        if (attempt.request?.completed == false)
                            throw P2pTransientFailure("Wi-Fi P2P create result is unresolved after Channel loss")
                        if (attempt.createSucceeded) {
                            recordCreatedGroupIdentity(attempt, group)
                            if (group == null || !ownsCleanupGroup(group, observedCreatedName))
                                throw P2pTransientFailure("Wi-Fi P2P startup group was lost during Channel recovery")
                        } else if (group != null) throw P2pResetRequiredException()
                    } else {
                        val previous = activeInfo
                            ?: throw P2pTransientFailure("Wi-Fi P2P active group is unavailable")
                        val refreshed = group?.let { refreshedGroupInfo(it, previous) }
                        if (continuityLost || group == null || refreshed == null ||
                            !canReuseOwnedP2pGroup(previous, refreshed, ownsCleanupGroup(group, previous.ssid)))
                            throw P2pTransientFailure("Wi-Fi P2P live group could not be verified after Channel loss")
                        activeInfo = refreshed
                    }
                    channelRecovering = false
                    stateLock.notifyAll()
                }
                diagnostic("Wi-Fi P2P Channel recovered epoch=$nextEpoch phase=${if (duringStartup) "startup" else "active"} groupVerified=${group != null}")
            } catch (failure: Exception) {
                synchronized(stateLock) {
                    channelFailure = when (failure) {
                        is SecurityException -> IOException("Wi-Fi P2P Channel recovery permission denied", failure)
                        is P2pResetRequiredException -> failure
                        is P2pTransientFailure -> failure
                        else -> P2pTransientFailure("Wi-Fi P2P Channel recovery failed", failure)
                    }
                    channelRecovering = false
                    stateLock.notifyAll()
                }
                diagnostic("Wi-Fi P2P Channel recovery stopped type=${failure.javaClass.simpleName}")
            } finally {
                // The replacement was registered before closing the old framework client.
                if (oldChannel !== synchronized(stateLock) { channel }) runCatching { oldChannel?.close() }
            }
        }
        reportRuntimeChannelFailure()
    }

    private fun refreshedGroupInfo(group: WifiP2pGroup, previous: WirelessHotspotInfo): WirelessHotspotInfo? {
        val iface = group.`interface`?.takeIf { it.isNotBlank() } ?: return null
        val addresses = interfaceAddresses(iface)
        val frequency = liveRadio.frequencyMHz(group, iface)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && frequency == null) return null
        return WirelessHotspotInfo(group.networkName.orEmpty(), group.passphrase.orEmpty(), groupSecurity(group),
            frequency?.let(::wifiFrequencyMhzToChannel) ?: 0, frequency,
            interfaceHardwareAddress(iface) ?: group.owner?.deviceAddress?.takeIf { it.isNotBlank() },
            iface, addresses.firstOrNull(), previous.bandLabel, WirelessHotspotBackend.WIFI_P2P, addresses,
            accessPointBssid = HotspotInterfaceBssid.readAccessPoint(iface))
    }

    private fun reportRuntimeChannelFailure() {
        val failure = synchronized(stateLock) {
            val cause = channelFailure ?: return
            if (closed || startAttempt != null || activeInfo == null || runtimeFailureReported) return
            runtimeFailureReported = true
            continuityLost = true
            val reason = if (cause is P2pTransientFailure) WirelessStartupFailure.HOTSPOT_NOT_READY
                else WirelessStartupFailure.HOTSPOT_CONFIGURATION
            WirelessStartupException(reason, cause.message ?: "Wi-Fi P2P Channel recovery failed")
                .also { it.initCause(cause) }
        }
        onRuntimeFailure(failure)
    }

    private fun submitCreate(attempt: StartAttempt, request: CreateRequest, name: String?,
        submit: (WifiP2pManager.Channel, WifiP2pManager.ActionListener) -> Unit,
    ) = synchronized(stateLock) {
        ensureStartActiveLocked(attempt)
        check(attempt.request?.completed != false) { "Previous P2P create is still unresolved" }
        val live = channel ?: throw P2pTransientFailure("Wi-Fi P2P Channel is unavailable")
        // Publish the request and hand it to the framework atomically with respect to Channel
        // loss. Recovery must never mistake a request waiting to be sent for an unknown result.
        attempt.request = request
        requestedName = name
        submit(live, createActionListener(attempt, request))
    }

    private fun createActionListener(
        attempt: StartAttempt,
        request: CreateRequest,
    ): WifiP2pManager.ActionListener = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            val activeChannel = attempt.channel
            val removeDetachedGroup = synchronized(stateLock) {
                if (request.completed) return
                request.completed = true
                attempt.createSucceeded = true
                if (startAttempt === attempt && attempt.request === request && !attempt.stopped && !closed) {
                    created = true
                    stateLock.notifyAll()
                    false
                } else true
            }
            if (removeDetachedGroup && activeChannel != null) {
                // A legacy system-named group may not have been observed before close. Capture
                // the first identity after our create acknowledgement, as the normal start does.
                p2pManager.requestGroupInfo(activeChannel) { group ->
                    recordCreatedGroupIdentity(attempt, group)
                    removeGroup(activeChannel, waitForCallback = false)
                }
            }
        }

        override fun onFailure(reason: Int) {
            synchronized(stateLock) {
                if (request.completed || attempt.request !== request) return
                request.completed = true
                request.failure = P2pCreateRejected(reason,
                    "Wi-Fi P2P createGroup failed: ${failureReason(reason)} (code=$reason)")
                stateLock.notifyAll()
            }
            diagnostic("Wi-Fi P2P create rejected code=$reason reason=${failureReason(reason)}")
        }
    }

    private fun failAttempt(attempt: StartAttempt, failure: IOException) {
        synchronized(stateLock) {
            if (startAttempt === attempt && !attempt.stopped && !closed) {
                if (attempt.failure == null) attempt.failure = failure
                stateLock.notifyAll()
            }
        }
    }

    private fun awaitGroupCreated(
        attempt: StartAttempt,
        request: CreateRequest,
        deadlineNanos: Long,
        timeoutMillis: Long,
    ) {
        synchronized(stateLock) {
            while (true) {
                ensureStartActiveLocked(attempt)
                attempt.failure?.let { throw it }
                request.failure?.let { throw it }
                if (attempt.createSucceeded) return

                val remainingNanos = remainingNanos(deadlineNanos)
                if (remainingNanos <= 0) {
                    throw P2pTransientFailure(
                        "Timed out after ${timeoutMillis}ms waiting for Wi-Fi P2P group creation",
                    )
                }
                waitNanos(remainingNanos)
            }
        }
    }

    private fun awaitUsableGroup(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        credentials: Credentials?,
        deadlineNanos: Long,
        timeoutMillis: Long,
    ): WirelessHotspotInfo {
        var lastReason = "group information was not available"
        val radio = liveRadio
        var reportedReason: String? = null
        var completeGroupSinceNanos: Long? = null
        // This HS5 firmware never exposes its GO frequency. Its system-selected group has
        // already been verified with an unspecified iAP2 channel; do not wait for a getter
        // that cannot settle. Credentials, ownership and the live interface remain required.
        val unknownChannelWaitMillis = if (Build.VERSION.SDK_INT == 28 &&
            Build.MODEL == "HONG QI" && Build.DEVICE == "t1c_acrn") 0L else 2_500L
        while (true) {
            ensureStartActive(attempt)
            val remainingNanos = remainingNanos(deadlineNanos)
            if (remainingNanos <= 0) {
                throw P2pTransientFailure(
                    "Timed out after ${timeoutMillis}ms waiting for a usable Wi-Fi P2P group: " +
                        lastReason,
                )
            }

            val group = requestGroupInfo(
                attempt = attempt,
                channel = channel,
                timeoutNanos = minOf(remainingNanos, REQUEST_POLL_NANOS),
            )
            if (group == null) {
                completeGroupSinceNanos = null
                synchronized(stateLock) { waitNanos(TimeUnit.MILLISECONDS.toNanos(100)) }
                continue
            }
            if (!group.isGroupOwner) {
                throw IOException("Wi-Fi P2P device became a group client instead of owner")
            }

            val networkName = group.networkName?.takeIf { it.isNotBlank() }
            if (observedCreatedName != null && networkName != observedCreatedName) {
                throw IOException("Wi-Fi Direct group changed during startup")
            }
            if (credentials != null && networkName != null && networkName != credentials.ssid) {
                throw IOException("Wi-Fi Direct returned an unexpected group")
            }
            val passphrase = group.passphrase?.takeIf { it.isNotBlank() }
                ?: credentials?.passphrase
            val interfaceName = group.getInterface()?.takeIf { it.isNotBlank() }
            val frequencyMHz = interfaceName?.let { radio.frequencyMHz(group, it) }
            val channelNumber = frequencyMHz?.let(::wifiFrequencyMhzToChannel)
            val detailsComplete = networkName != null && passphrase != null && interfaceName != null
            if (!detailsComplete) completeGroupSinceNanos = null
            else if (completeGroupSinceNanos == null) completeGroupSinceNanos = System.nanoTime()
            // Android 9 firmware may expose a usable GO but no operating frequency, even
            // through its driver. The existing iAP2 endpoint accepts 0 for an unspecified
            // channel (also used by the manual-hotspot path); keep the frequency unknown.
            // Give the real driver reading time to settle before using that representation.
            val unspecifiedChannel = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                detailsComplete &&
                frequencyMHz == null && System.nanoTime() - requireNotNull(completeGroupSinceNanos) >=
                    TimeUnit.MILLISECONDS.toNanos(unknownChannelWaitMillis)
            if (
                !detailsComplete || (channelNumber == null && !unspecifiedChannel)
            ) {
                lastReason = "incomplete group details name=${networkName != null} key=${passphrase != null} " +
                    "interface=${interfaceName != null} frequencyMHz=${frequencyMHz ?: "unknown"}; ${radio.detail}"
                if (lastReason != reportedReason) { diagnostic("Wi-Fi P2P waiting: $lastReason"); reportedReason = lastReason }
                synchronized(stateLock) { waitNanos(TimeUnit.MILLISECONDS.toNanos(100)) }
                continue
            }
            diagnostic(if (unspecifiedChannel) "Wi-Fi P2P frequency unavailable; channel=0 (unspecified), system credentials retained"
                else "Wi-Fi P2P ${radio.detail}")
            val band = when {
                frequencyMHz == null -> "Auto (channel unspecified)"
                is5Ghz(frequencyMHz) -> "5 GHz"
                frequencyMHz in 2412..2484 -> "2.4 GHz"
                else -> throw IOException("Wi-Fi P2P returned an unsupported band at ${frequencyMHz}MHz")
            }

            val hostAddress = awaitInterfaceAddress(attempt, requireNotNull(interfaceName), deadlineNanos)
                ?: requestConnectionAddress(
                    attempt = attempt,
                    channel = channel,
                    timeoutNanos = minOf(remainingNanos(deadlineNanos), REQUEST_POLL_NANOS),
                )
            if (hostAddress == null) {
                lastReason = "interface $interfaceName has no usable IPv6 or IPv4 address"
                continue
            }

            return WirelessHotspotInfo(
                ssid = requireNotNull(networkName),
                passphrase = requireNotNull(passphrase),
                security = groupSecurity(group),
                channel = channelNumber ?: 0,
                frequencyMHz = frequencyMHz,
                bssid = interfaceHardwareAddress(interfaceName)
                    ?: group.owner?.deviceAddress?.takeIf { it.isNotBlank() },
                interfaceName = interfaceName,
                hostAddress = hostAddress,
                bandLabel = band,
                backend = WirelessHotspotBackend.WIFI_P2P,
                hostAddresses = (listOf(hostAddress) + interfaceAddresses(interfaceName)).distinct(),
                // The GO interface's MAC is the AP hint. The owner's P2P device address, used as
                // an AirPlay identity fallback above, is not proof of the AP's address.
                accessPointBssid = HotspotInterfaceBssid.readAccessPoint(interfaceName),
            )
        }
    }

    private fun requestGroupInfo(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        timeoutNanos: Long,
        requireResponse: Boolean = false,
    ): WifiP2pGroup? {
        val result = AtomicReference<WifiP2pGroup?>()
        val latch = CountDownLatch(1)
        p2pManager.requestGroupInfo(currentChannel(attempt)) {
            // Keep the first identity returned after our successful creation. A later global
            // broadcast may describe a replacement group belonging to another app.
            recordCreatedGroupIdentity(attempt, it)
            result.set(it)
            latch.countDown()
        }
        if (!await(latch, minOf(timeoutNanos, remainingNanos(startupDeadlineNanos)))) {
            if (requireResponse) throw P2pTransientFailure("Wi-Fi Direct did not respond")
            return null
        }
        ensureStartActive(attempt)
        return result.get()
    }

    private fun recordCreatedGroupIdentity(attempt: StartAttempt, group: WifiP2pGroup?) {
        if (attempt.createSucceeded && observedCreatedName == null && group?.isGroupOwner == true &&
            !group.networkName.isNullOrBlank() && ownsCurrentAttempt() &&
            (requestedName == null || group.networkName == requestedName)) {
            observedCreatedName = group.networkName
            if (!ownership.edit().putString("owned_ssid", group.networkName).commit()) {
                failAttempt(attempt, IOException("Could not record Wi-Fi P2P group ownership"))
            }
        }
    }

    private fun requestConnectionAddress(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        timeoutNanos: Long,
    ): InetAddress? {
        val result = AtomicReference<WifiP2pInfo?>()
        val latch = CountDownLatch(1)
        p2pManager.requestConnectionInfo(currentChannel(attempt)) {
            result.set(it)
            latch.countDown()
        }
        if (!await(latch, minOf(timeoutNanos, remainingNanos(startupDeadlineNanos)))) return null
        ensureStartActive(attempt)
        val info = result.get() ?: return null
        if (!info.groupFormed) return null
        return info.groupOwnerAddress?.takeUnless(InetAddress::isAnyLocalAddress)
    }

    private fun await(latch: CountDownLatch, timeoutNanos: Long): Boolean = try {
        val waitNanos = timeoutNanos.coerceAtLeast(1L)
        latch.await(waitNanos, TimeUnit.NANOSECONDS)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Interrupted while waiting for Wi-Fi P2P", interrupted)
    }

    private fun interfaceAddresses(interfaceName: String): List<InetAddress> {
        val networkInterface = networkInterface(interfaceName) ?: return emptyList()
        // Keep the proven IPv4 primary, and include the same interface's scoped IPv6 path
        // for discovery/listening. Never borrow an address from the station interface.
        return existingWifiHostAddresses(Collections.list(networkInterface.inetAddresses), networkInterface.index)
    }

    private fun awaitInterfaceAddress(
        attempt: StartAttempt,
        interfaceName: String,
        startupDeadlineNanos: Long,
    ): InetAddress? {
        // Group creation may precede interface addressing. A usable IPv4 address does
        // not need to wait for IPv6; both families are collected once the group is ready.
        val addressDeadline = minOf(startupDeadlineNanos, deadlineAfter(2_000))
        return awaitP2pInterfaceAddress(addressDeadline,
            readAddress = { interfaceAddresses(interfaceName).firstOrNull() },
            ensureActive = { ensureStartActive(attempt) })
    }

    private data class Station(val state: SupplicantState?, val reportedFrequency: Int?) {
        // A vendor may retain the last frequency after disconnecting. Only align to an
        // established Wi-Fi association; internet validation is deliberately irrelevant.
        val alignmentFrequency: Int?
            get() = reportedFrequency?.takeIf { state == SupplicantState.COMPLETED && it > 0 }
    }

    @Suppress("DEPRECATION")
    private fun readStation(): Station = runCatching {
        val info = appContext.getSystemService(WifiManager::class.java)?.connectionInfo
        Station(info?.supplicantState, info?.frequency?.takeIf { it > 0 })
    }.getOrDefault(Station(null, null))

    /** Wi-Fi enable is asynchronous; do not race P2P creation against radio startup. */
    private fun awaitWifiEnabled(attempt: StartAttempt, startupDeadlineNanos: Long) {
        ensureStartActive(attempt)
        when (WifiRadioStartup.requestEnable(appContext)) {
            WifiRadioStartup.READY -> return
            WifiRadioStartup.USER_ACTION -> throw IOException("Turn on Wi-Fi in the head unit's settings, then reconnect")
            WifiRadioStartup.UNAVAILABLE -> throw IOException("Wi-Fi radio is unavailable in the head unit")
        }
        diagnostic("Wi-Fi P2P waiting for Wi-Fi radio to enable")
        val wifi = appContext.getSystemService(WifiManager::class.java)
            ?: throw IOException("WifiManager is unavailable")
        val deadline = minOf(startupDeadlineNanos, deadlineAfter(10_000))
        while (true) {
            ensureStartActive(attempt)
            if (wifi.isWifiEnabled) {
                diagnostic("Wi-Fi P2P radio ready")
                return
            }
            val remaining = remainingNanos(deadline)
            if (remaining == 0L) throw P2pTransientFailure("Wi-Fi did not turn on before connection timeout; check the head unit's Wi-Fi settings")
            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
                waitNanos(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)))
            }
        }
    }

    private fun checkPrerequisites(station: Station) {
        val wifi = appContext.getSystemService(WifiManager::class.java)
        val fiveGhzSupported = runCatching { wifi?.is5GHzBandSupported }.getOrNull()
        val wifiEnabled = runCatching { wifi?.isWifiEnabled }.getOrNull()
        val locationEnabled = runCatching {
            appContext.getSystemService(LocationManager::class.java)?.isLocationEnabled
        }.getOrNull()
        val required = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION
        val granted = appContext.checkSelfPermission(required) == PackageManager.PERMISSION_GRANTED
        val locationAccessMode = if (Build.VERSION.SDK_INT in 29..32) runCatching {
            appContext.getSystemService(AppOpsManager::class.java)?.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_FINE_LOCATION, android.os.Process.myUid(), appContext.packageName)
        }.getOrNull() else null
        diagnostic("Wi-Fi P2P preflight wifiEnabled=$wifiEnabled locationEnabled=$locationEnabled permissionGranted=$granted locationAccessMode=${locationAccessMode ?: "unknown"} stationMHz=${station.alignmentFrequency ?: "unknown"} stationState=${station.state ?: "unknown"} reportedStationMHz=${station.reportedFrequency ?: "unknown"} fiveGhzSupported=${fiveGhzSupported ?: "unknown"}")
        if (!granted) throw IOException(if (Build.VERSION.SDK_INT >= 33)
            "Allow Nearby devices for Autunn Play in the head unit's app permissions"
            else "Allow precise Location for Autunn Play in the head unit's app permissions")
        if (wifiEnabled == false) throw IOException("Turn on Wi-Fi in the head unit's settings, then reconnect")
        // Location mode is diagnostic only: AOSP createGroup does not require it to be on.
        // Do not block firmware where Wi-Fi Direct works with Location services disabled.
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun logP2pState(attempt: StartAttempt, channel: WifiP2pManager.Channel) {
        val result = AtomicReference<Int?>()
        val latch = CountDownLatch(1)
        p2pManager.requestP2pState(channel) { state -> result.set(state); latch.countDown() }
        await(latch, REQUEST_POLL_NANOS)
        ensureStartActive(attempt)
        diagnostic("Wi-Fi P2P frameworkState=${result.get() ?: "unknown"}")
    }

    private fun interfaceHardwareAddress(interfaceName: String): String? =
        networkInterface(interfaceName)
            ?.hardwareAddress
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun networkInterface(interfaceName: String): NetworkInterface? = try {
        NetworkInterface.getByName(interfaceName)
    } catch (_: SocketException) {
        null
    }

    private fun groupSecurity(group: WifiP2pGroup): Iap2WirelessSecurity {
        if (Build.VERSION.SDK_INT < 36) return Iap2WirelessSecurity.WPA_WPA2
        return when (group.securityType) {
            WifiP2pGroup.SECURITY_TYPE_WPA2_PSK -> Iap2WirelessSecurity.WPA_WPA2
            WifiP2pGroup.SECURITY_TYPE_WPA3_COMPATIBILITY ->
                Iap2WirelessSecurity.WPA3_TRANSITION
            WifiP2pGroup.SECURITY_TYPE_WPA3_SAE -> Iap2WirelessSecurity.WPA3_ONLY
            // Several vendor supplicants report an authentication key management the framework
            // cannot classify, which surfaces as UNKNOWN rather than as a concrete cipher suite.
            // The group itself is still the plain WPA2-PSK group this device creates by default,
            // so keep the assumption every older Android release relies on instead of aborting a
            // bring-up that has already resolved its SSID, band, interface and host address.
            WifiP2pGroup.SECURITY_TYPE_UNKNOWN -> {
                diagnostic("Wi-Fi P2P security type not reported by the framework; assuming WPA2-PSK")
                Iap2WirelessSecurity.WPA_WPA2
            }
            else -> throw IOException(
                "Unsupported Wi-Fi P2P security type: ${group.securityType}",
            )
        }
    }

    private fun randomCredentials(): Credentials = Credentials(
        ssid = "$ssidPrefix${randomToken(4)}",
        passphrase = randomToken(16),
    )

    private fun randomToken(length: Int): String =
        buildString(length) {
            repeat(length) {
                append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)])
            }
        }

    private fun ensureStartActive(attempt: StartAttempt) {
        synchronized(stateLock) {
            ensureStartActiveLocked(attempt)
        }
    }

    private fun ensureStartActiveLocked(attempt: StartAttempt) {
        while (channelRecovering && !closed && !attempt.stopped) {
            val remaining = remainingNanos(startupDeadlineNanos)
            if (remaining == 0L) throw P2pTransientFailure("Wi-Fi P2P Channel recovery exceeded startup deadline")
            waitNanos(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)))
        }
        channelFailure?.let { throw it }
        if (closed) throw IOException("WifiP2pGroupManager closed while starting")
        if (startAttempt !== attempt) throw IOException("Wi-Fi P2P startup was cancelled")
        if (attempt.stopped) throw IOException("Wi-Fi P2P group stopped before startup completed")
        if (ownershipClaimed && !ownsCurrentAttempt()) throw IOException("Wi-Fi P2P startup superseded by a newer session")
        attempt.failure?.let { throw it }
    }

    private fun claimOwnership() = synchronized(ownership) {
        if (!ownership.edit().putString("active_owner", ownerToken).commit()) {
            throw IOException("Could not record Wi-Fi P2P attempt ownership")
        }
        ownershipClaimed = true
    }

    private fun ownsCurrentAttempt(): Boolean = ownership.getString("active_owner", null) == ownerToken

    private fun cleanupFailedStart(attempt: StartAttempt) = synchronized(cleanupLock) {
        val failedChannel: WifiP2pManager.Channel?
        val failedThread: HandlerThread?
        val removeGroup: Boolean
        synchronized(stateLock) {
            attempt.stopped = true
            stateLock.notifyAll()
            failedChannel = attempt.channel
            failedThread = attempt.thread
            removeGroup = attempt.createSucceeded
        }
        unregisterContinuityMonitor()
        if (synchronized(stateLock) { attempt.request?.completed == false }) {
            throw IOException("Wi-Fi P2P group creation cleanup is awaiting its callback")
        }
        if (removeGroup && failedChannel != null) {
            removeGroupBlocking(failedChannel)
            synchronized(stateLock) { created = false; attempt.createSucceeded = false }
        }
        if (failedChannel != null) clearLegacyChannel(failedChannel)
        failedChannel?.close()
        synchronized(stateLock) {
            if (channel === failedChannel) channel = null
            attempt.channel = null
        }
        stopCallbackThread(failedThread)
        synchronized(stateLock) {
            if (callbackThread === failedThread) callbackThread = null
            attempt.thread = null
            if (startAttempt === attempt) startAttempt = null
        }
    }

    private fun removeGroupBlocking(channel: WifiP2pManager.Channel, expectedName: String? = observedCreatedName ?: requestedName) {
        val deadline = deadlineAfter(REMOVE_GROUP_TIMEOUT_MILLIS)
        val current = cleanupGroupInfo(channel, deadline) ?: return
        if (ownsCurrentAttempt() && expectedName.isNullOrBlank()) {
            throw IOException("Wi-Fi P2P group identity is not yet confirmed for removal")
        }
        if (!ownsCleanupGroup(current, expectedName)) {
            diagnostic("Wi-Fi P2P cleanup skipped=another_app_owns_group")
            return
        }
        val completed = CountDownLatch(1)
        val rejection = AtomicReference<IOException?>()
        try {
            p2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { completed.countDown() }
                override fun onFailure(reason: Int) {
                    rejection.set(IOException("Wi-Fi P2P remove rejected code=$reason reason=${failureReason(reason)}"))
                    completed.countDown()
                }
            })
            if (!completed.await(remainingNanos(deadline), TimeUnit.NANOSECONDS)) {
                throw IOException("Wi-Fi P2P group removal callback timed out")
            }
            rejection.get()?.let { throw it }
            // onSuccess acknowledges the request; only a subsequent query proves the old AP is
            // gone. An unrelated replacement is also safe, and must never be removed here.
            while (true) {
                val remaining = cleanupGroupInfo(channel, deadline) ?: return
                if (!ownsCleanupGroup(remaining, expectedName)) return
                if (remainingNanos(deadline) == 0L) throw IOException("Wi-Fi P2P group removal is still pending")
                Thread.sleep(minOf(100L, TimeUnit.NANOSECONDS.toMillis(remainingNanos(deadline)).coerceAtLeast(1)))
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while removing Wi-Fi P2P group", interrupted)
        } catch (failure: RuntimeException) {
            throw IOException("Wi-Fi P2P removeGroup could not be issued", failure)
        }
    }

    private fun ownsCleanupGroup(group: WifiP2pGroup, expectedName: String?): Boolean =
        ownsCurrentAttempt() && group.isGroupOwner && !expectedName.isNullOrBlank() && group.networkName == expectedName

    private fun cleanupGroupInfo(channel: WifiP2pManager.Channel, deadline: Long): WifiP2pGroup? {
        val result = AtomicReference<WifiP2pGroup?>()
        val completed = CountDownLatch(1)
        try {
            if (remainingNanos(deadline) == 0L) throw IOException("Wi-Fi P2P group removal confirmation timed out")
            p2pManager.requestGroupInfo(channel) { result.set(it); completed.countDown() }
            if (!completed.await(remainingNanos(deadline), TimeUnit.NANOSECONDS)) {
                throw IOException("Wi-Fi P2P group removal confirmation timed out")
            }
            return result.get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while confirming Wi-Fi P2P group removal", interrupted)
        } catch (failure: RuntimeException) {
            throw IOException("Wi-Fi P2P group removal could not be confirmed", failure)
        }
    }

    private fun removeGroup(channel: WifiP2pManager.Channel, waitForCallback: Boolean,
        expectedName: String? = observedCreatedName ?: requestedName) {
        val latch = CountDownLatch(1)
        try {
            p2pManager.requestGroupInfo(channel) { current ->
                if (current == null) {
                    latch.countDown()
                    return@requestGroupInfo
                }
                // Cleanup needs this attempt's exact identity. The reinstall namespace used at
                // startup is broader and could also match a newer DiPlay session.
                val ours = ownsCurrentAttempt() && current.isGroupOwner && !expectedName.isNullOrBlank() &&
                    current.networkName == expectedName
                if (!ours) {
                    diagnostic("Wi-Fi P2P cleanup skipped=another_app_owns_group")
                    latch.countDown()
                    return@requestGroupInfo
                }
                p2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        latch.countDown()
                    }

                    override fun onFailure(reason: Int) {
                        diagnostic("Wi-Fi P2P remove rejected code=$reason reason=${failureReason(reason)}")
                        latch.countDown()
                    }
                })
            }
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Wi-Fi P2P removeGroup could not be issued", failure)
            latch.countDown()
        }
        if (!waitForCallback) return
        try {
            latch.await(REMOVE_GROUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun waitNanos(nanos: Long) {
        val millis = nanos / NANOS_PER_MILLISECOND
        val remainder = (nanos % NANOS_PER_MILLISECOND).toInt()
        try {
            stateLock.wait(millis, remainder)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for Wi-Fi P2P", interrupted)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun remainingNanos(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private fun failureReason(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi P2P is unsupported"
        WifiP2pManager.BUSY -> "Wi-Fi P2P is busy"
        WifiP2pManager.ERROR -> "generic error"
        WifiP2pManager.NO_PERMISSION -> "permission denied"
        else -> "reason $reason"
    }

    private fun is5Ghz(frequencyMHz: Int): Boolean = frequencyMHz in 5150..5895

    private class StartAttempt {
        var channel: WifiP2pManager.Channel? = null
        var thread: HandlerThread? = null
        var createSucceeded = false
        var request: CreateRequest? = null
        var failure: IOException? = null
        var stopped = false
    }

    private class CreateRequest {
        var completed = false
        var failure: P2pCreateRejected? = null
    }

    private class Credentials(
        val ssid: String,
        val passphrase: String,
    )

    private companion object {
        const val TAG = "xcertplay-usb"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val REMOVE_GROUP_TIMEOUT_MILLIS = 2_000L
        const val CHANNEL_RECOVERY_TIMEOUT_MILLIS = 2_000L
        val REQUEST_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(500)
        const val TOKEN_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    }
}

/** The live identity and usable interface must still match the manager's acquired group. */
internal fun canReuseOwnedP2pGroup(previous: WirelessHotspotInfo, current: WirelessHotspotInfo,
    ownsGroup: Boolean): Boolean = ownsGroup &&
    previous.backend == WirelessHotspotBackend.WIFI_P2P && current.backend == WirelessHotspotBackend.WIFI_P2P &&
    current.ssid.isNotBlank() && current.ssid == previous.ssid &&
    current.passphrase.isNotBlank() && current.passphrase == previous.passphrase &&
    current.security == previous.security &&
    !current.interfaceName.isNullOrBlank() && current.interfaceName == previous.interfaceName &&
    (previous.bssid == null || current.bssid == previous.bssid) &&
    (previous.frequencyMHz == null || current.frequencyMHz == previous.frequencyMHz) &&
    current.hostAddress != null && !current.hostAddress.isAnyLocalAddress && !current.hostAddress.isLoopbackAddress &&
    current.hostAddress in current.hostAddresses
