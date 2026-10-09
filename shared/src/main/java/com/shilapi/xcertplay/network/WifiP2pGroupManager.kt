package com.shilapi.xcertplay.network

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
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
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.UnknownHostException
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
) : WirelessHotspotManager {
    init {
        require(WifiP2pChannels.isValid(preferredChannel)) { "Unsupported Wi-Fi Direct channel: $preferredChannel" }
    }
    private val appContext = context.applicationContext
    private val p2pManager = appContext.getSystemService(WifiP2pManager::class.java)
        ?: throw IllegalStateException("WifiP2pManager is unavailable")
    private val stateLock = Object()
    private val random = SecureRandom()
    private val configurationMemory = P2pConfigurationMemory(appContext)
    private var rememberedAttempt: P2pConfigurationMemory.Record? = null
    private var pendingSuccess: (() -> Boolean)? = null
    private var carPlayConfirmed = false
    private val ownership = appContext.getSharedPreferences("carplay_wifi_p2p", Context.MODE_PRIVATE)
    private val ownerToken = java.util.UUID.randomUUID().toString()
    private var ownershipClaimed = false
    private val ssidPrefix by lazy {
        // ANDROID_ID is scoped to the signing key, user and device on our supported OS versions.
        val id = runCatching { Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: ownership.getString("fallback_id", null)
            ?: java.util.UUID.randomUUID().toString().also {
                check(ownership.edit().putString("fallback_id", it).commit())
            }
        P2pOwnership.prefix(appContext.packageName, id)
    }

    private var channel: WifiP2pManager.Channel? = null
    private var callbackThread: HandlerThread? = null
    private var created = false
    private var closed = false
    private var startAttempt: StartAttempt? = null
    @Volatile private var observedCreatedName: String? = null
    @Volatile private var requestedName: String? = null
    private val liveRadio = P2pGroupRadioInfo()

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
                        "reportedP2pClients=${group.clientList.size} association=unknown legacyClients=not_exposed " +
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
        val credentials = randomCredentials()

        try {
            awaitWifiEnabled(attempt, deadlineNanos)
            val station = readStation()
            val stationFrequency = station.alignmentFrequency
            checkPrerequisites(station)
            val remembered = configurationMemory.read()
            val preferred = remembered?.takeIf {
                !legacy && preferredChannel == WifiP2pChannels.AUTO && it.stationMHz == stationFrequency
            }
            diagnostic("Wi-Fi P2P channel preference=${if (preferredChannel == WifiP2pChannels.AUTO) "auto" else preferredChannel} " +
                "frequencyMHz=${WifiP2pChannels.frequencyMhz(preferredChannel) ?: "auto"}")
            diagnostic(when {
                legacy -> "Wi-Fi P2P remembered skipped=api28_system_channel"
                preferredChannel != WifiP2pChannels.AUTO -> "Wi-Fi P2P remembered skipped=manual_channel"
                preferred != null -> "Wi-Fi P2P remembered first mode=${preferred.request.mode} frequencyMHz=${preferred.request.frequencyMHz ?: "auto"}"
                remembered != null -> "Wi-Fi P2P remembered skipped=station_channel_changed"
                else -> "Wi-Fi P2P remembered unavailable"
            })
            val p2pChannel = p2pManager.initialize(
                appContext,
                thread.looper,
                createChannelListener(attempt),
            )
            attempt.channel = p2pChannel
            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
                channel = p2pChannel
                callbackThread = thread
            }

            if (!legacy) logP2pState(attempt, p2pChannel)
            else diagnostic("Wi-Fi P2P API 28 system credentials and automatic channel; no hotspot fallback")
            // Preferences disappear on reinstall, but the scoped namespace survives.
            val existing = requestGroupInfo(attempt, p2pChannel, REQUEST_POLL_NANOS, requireResponse = true)
            diagnostic("Wi-Fi P2P existingGroup=${existing != null}")
            if (existing != null) {
                val recordedName = ownership.getString("owned_ssid", null)
                val knownGroup = P2pOwnership.canReclaim(existing.isGroupOwner, existing.networkName,
                    recordedName, ssidPrefix)
                if (!knownGroup) throw P2pResetRequiredException()
                ensureStartActive(attempt)
                claimOwnership()
                diagnostic("Wi-Fi P2P reclaiming retained owned group")
                removeGroupBlocking(p2pChannel, existing.networkName)
                val removalDeadline = minOf(deadlineNanos, deadlineAfter(REMOVE_GROUP_TIMEOUT_MILLIS))
                while (requestGroupInfo(attempt, p2pChannel, REQUEST_POLL_NANOS, requireResponse = true) != null) {
                    if (remainingNanos(removalDeadline) == 0L) throw IOException("Wi-Fi Direct reset did not finish")
                    synchronized(stateLock) { waitNanos(TimeUnit.MILLISECONDS.toNanos(100)) }
                }
            } else claimOwnership()

            val selectedChannel = WifiP2pChannels.selectExperimentalChannel(preferredChannel, stationFrequency)
            val effectiveChannel = if (legacy && (selectedChannel != WifiP2pChannels.AUTO || channelCleanupPending())) {
                // Recheck the global group immediately before touching shared channel restrictions.
                if (requestGroupInfo(attempt, p2pChannel, REQUEST_POLL_NANOS, requireResponse = true) != null)
                    throw P2pResetRequiredException()
                P2pChannelRestriction.acquire(ownerToken, selectedChannel, deadlineNanos,
                    ::channelCleanupPending, ::recordChannelCleanup,
                    { ownsCurrentAttempt() && !attempt.stopped && !closed },
                    { value, callback -> setLegacyChannel(p2pChannel, value, callback) }, diagnostic)
            } else selectedChannel
            val creation = if (legacy) createLegacyGroup(attempt, p2pChannel, deadlineNanos, timeoutMillis)
                .copy(frequencyMHz = WifiP2pChannels.frequencyMhz(effectiveChannel))
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
                    if (remainingNanos(deadlineNanos) == 0L) throw IOException("Wi-Fi Direct startup timed out")
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
                    requestedName = if (config == null) null else credentials.ssid
                    synchronized(stateLock) {
                        ensureStartActiveLocked(attempt)
                        attempt.request = request
                    }
                    diagnostic("Wi-Fi P2P create mode=${selection.mode} frequencyMHz=${selection.frequencyMHz ?: "auto"}")
                    // The API 29 overload with null config uses system-generated credentials
                    // without requesting a persistent group, unlike the older two-argument API.
                    val usingRemembered = preferred?.request == selection
                    if (usingRemembered) synchronized(stateLock) { rememberedAttempt = preferred }
                    try {
                        p2pManager.createGroup(p2pChannel, config, createActionListener(attempt, request))
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
                credentials = if (creation.mode == P2pCreationMode.SYSTEM_DEFAULT) null else credentials,
                deadlineNanos = deadlineNanos,
                timeoutMillis = timeoutMillis,
            )
            if (!ownership.edit().putString("owned_ssid", group.ssid).commit()) {
                throw IOException("Could not record Wi-Fi P2P group ownership")
            }
            diagnostic("Wi-Fi P2P ready mode=${creation.mode} band=${group.bandLabel} channel=${group.channel} frequencyMHz=${group.frequencyMHz} atNs=${System.nanoTime()}")
            diagnostic("Wi-Fi P2P channel requestedMHz=${creation.frequencyMHz ?: "auto"} actualMHz=${group.frequencyMHz} matched=${creation.frequencyMHz?.let { it == group.frequencyMHz } ?: "system_selected"}")
            if (preferredChannel != WifiP2pChannels.AUTO && creation.frequencyMHz != null &&
                group.frequencyMHz != null && group.frequencyMHz != creation.frequencyMHz) {
                throw P2pChannelUnavailableException(preferredChannel,
                    "The car selected channel ${group.channel} instead.")
            }
            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
                created = true
                startAttempt = null
                // Manual experiments must not replace the proven automatic configuration.
                pendingSuccess = if (preferredChannel == WifiP2pChannels.AUTO && group.frequencyMHz != null) {
                    {
                        configurationMemory.remember(creation, requireNotNull(group.frequencyMHz), stationFrequency)
                    }
                } else null
            }
            return group
        } catch (failure: Exception) {
            diagnostic("Wi-Fi P2P startup stopped type=${failure.javaClass.simpleName}")
            cleanupFailedStart(attempt)
            if (failure is P2pResetRequiredException) throw failure
            // Keep radio/configuration failures visible instead of rebuilding the controller
            // forever. The existing UI offers an explicit retry after permissions/settings change.
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
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

    private fun createLegacyGroup(attempt: StartAttempt, channel: WifiP2pManager.Channel,
        deadlineNanos: Long, timeoutMillis: Long): P2pCreationRequest {
        // Retry only an explicit BUSY rejection, never an unanswered or successful request.
        for (index in 0..1) {
            ensureStartActive(attempt)
            if (remainingNanos(deadlineNanos) == 0L) throw IOException("Wi-Fi Direct startup timed out")
            val request = CreateRequest()
            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
                attempt.request = request
                requestedName = null
            }
            diagnostic("Wi-Fi P2P create mode=SYSTEM_DEFAULT api=28 attempt=${index + 1}")
            try {
                p2pManager.createGroup(channel, createActionListener(attempt, request))
                awaitGroupCreated(attempt, request, deadlineNanos, timeoutMillis)
                return P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT)
            } catch (failure: P2pCreateRejected) {
                if (failure.reason != WifiP2pManager.BUSY || index != 0) throw failure
                synchronized(stateLock) {
                    waitNanos(minOf(remainingNanos(deadlineNanos), TimeUnit.MILLISECONDS.toNanos(500)).coerceAtLeast(1))
                }
                ensureStartActive(attempt)
                checkPrerequisites(readStation())
                if (requestGroupInfo(attempt, channel, REQUEST_POLL_NANOS, requireResponse = true) != null) {
                    throw P2pResetRequiredException()
                }
            }
        }
        error("No Wi-Fi Direct creation attempted")
    }

    override fun close() {
        val attempt: StartAttempt?
        val activeChannel: WifiP2pManager.Channel?
        val activeThread: HandlerThread?
        val removeGroup: Boolean
        synchronized(stateLock) {
            if (closed) return
            closed = true
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
            channel = null
            callbackThread = null
            startAttempt = null
        }

        if (removeGroup && activeChannel != null) {
            removeGroupBlocking(activeChannel)
        }
        if (activeChannel != null) clearLegacyChannel(activeChannel)
        activeChannel?.close()
        activeThread?.quitSafely()
    }

    private fun createChannelListener(
        attempt: StartAttempt,
    ): WifiP2pManager.ChannelListener = object : WifiP2pManager.ChannelListener {
        override fun onChannelDisconnected() {
            failAttempt(attempt, IOException("Wi-Fi P2P channel disconnected"))
        }
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
                removeGroup(activeChannel, waitForCallback = false)
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
                    throw IOException(
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
        val radio = P2pGroupRadioInfo()
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
                throw IOException(
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
        p2pManager.requestGroupInfo(channel) {
            // Keep the first identity returned after our successful creation. A later global
            // broadcast may describe a replacement group belonging to another app.
            if (attempt.createSucceeded && observedCreatedName == null && it?.isGroupOwner == true &&
                !it.networkName.isNullOrBlank() && ownsCurrentAttempt() &&
                (requestedName == null || it.networkName == requestedName)) {
                observedCreatedName = it.networkName
                if (!ownership.edit().putString("owned_ssid", it.networkName).commit()) {
                    failAttempt(attempt, IOException("Could not record Wi-Fi P2P group ownership"))
                }
            }
            result.set(it)
            latch.countDown()
        }
        if (!await(latch, timeoutNanos)) {
            if (requireResponse) throw IOException("Wi-Fi Direct did not respond")
            return null
        }
        ensureStartActive(attempt)
        return result.get()
    }

    private fun requestConnectionAddress(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        timeoutNanos: Long,
    ): InetAddress? {
        val result = AtomicReference<WifiP2pInfo?>()
        val latch = CountDownLatch(1)
        p2pManager.requestConnectionInfo(channel) {
            result.set(it)
            latch.countDown()
        }
        if (!await(latch, timeoutNanos)) return null
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

    private fun interfaceAddress(interfaceName: String): InetAddress? {
        val networkInterface = networkInterface(interfaceName) ?: return null
        var ipv4: InetAddress? = null
        var ipv6: InetAddress? = null
        for (address in Collections.list(networkInterface.inetAddresses)) {
            if (address is Inet6Address && address.isLinkLocalAddress && ipv6 == null) {
                ipv6 = try {
                    if (address.scopeId == networkInterface.index) address
                    else Inet6Address.getByAddress(null, address.address, networkInterface)
                } catch (_: UnknownHostException) {
                    null
                }
            }
            if (address is Inet4Address && !address.isLoopbackAddress &&
                !address.isAnyLocalAddress && ipv4 == null) {
                ipv4 = address
            }
        }
        // HS5 exposes IPv6 link-local before its group IPv4 address. Prefer the usable
        // group IPv4 address so discovery and the advertised AirPlay listener agree.
        return ipv4 ?: ipv6
    }

    private fun awaitInterfaceAddress(
        attempt: StartAttempt,
        interfaceName: String,
        startupDeadlineNanos: Long,
    ): InetAddress? {
        // Group creation precedes IPv6 link-local configuration on some head units.
        // Give IPv6 a bounded chance to appear before falling back to IPv4.
        val addressDeadline = minOf(startupDeadlineNanos, deadlineAfter(2_000))
        while (true) {
            ensureStartActive(attempt)
            val address = interfaceAddress(interfaceName)
            if (address is Inet6Address || remainingNanos(addressDeadline) <= 0) return address
            Thread.sleep(100)
        }
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
            if (remaining == 0L) throw IOException("Wi-Fi did not turn on before connection timeout; check the head unit's Wi-Fi settings")
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
            "Allow Nearby devices for FAW Play in the head unit's app permissions"
            else "Allow precise Location for FAW Play in the head unit's app permissions")
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

    private fun cleanupFailedStart(attempt: StartAttempt) {
        val failedChannel: WifiP2pManager.Channel?
        val failedThread: HandlerThread?
        val removeGroup: Boolean
        synchronized(stateLock) {
            if (startAttempt === attempt) startAttempt = null
            attempt.stopped = true
            stateLock.notifyAll()
            failedChannel = attempt.channel
            failedThread = attempt.thread
            removeGroup = attempt.createSucceeded
            created = false
            if (channel === failedChannel) channel = null
            if (callbackThread === failedThread) callbackThread = null
        }
        if (removeGroup && failedChannel != null) {
            removeGroupBlocking(failedChannel)
        }
        if (failedChannel != null) clearLegacyChannel(failedChannel)
        failedChannel?.close()
        failedThread?.quitSafely()
    }

    private fun removeGroupBlocking(channel: WifiP2pManager.Channel, expectedName: String? = observedCreatedName ?: requestedName) {
        removeGroup(channel, waitForCallback = true, expectedName = expectedName)
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
        val REQUEST_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(500)
        const val TOKEN_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    }
}
