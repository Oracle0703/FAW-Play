package com.shilapi.xcertplay.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.airplay.isInternalAirPlayPeer
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.ClusterContentProgress
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the AirPlay TCP listener for both NCM/VPN and local-only Wi-Fi transports.
 *
 * The wired path also owns the Android VPN tunnel and NCM IPv6 bridge. VPN consent is requested
 * with [prepare] before binding.
 */
class CarPlayVpnService : VpnService() {
    inner class LocalBinder : Binder() {
        val service: CarPlayVpnService get() = this@CarPlayVpnService
    }

    sealed class AttachResult {
        data object Started : AttachResult()
        data object AlreadyStarted : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }

    private data class AirPlayAttachment(
        val address: InetAddress,
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: MfiAuthenticator?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
        val additionalAddresses: List<InetAddress> = emptyList(),
        val listenerIdentity: AirPlayListenerIdentity? = null,
    )

    private val binder = LocalBinder()
    private val active = AtomicBoolean(false)
    private val sessionsLock = Any()
    private val releaseLock = Any()
    private val releaseScheduled = AtomicBoolean(false)
    private var releaseRequestedGeneration = 0
    @Volatile private var destroyed = false
    private var releaseInProgress = false
    private val sessions = mutableSetOf<AirPlaySession>()
    private val closingSessions = mutableSetOf<AirPlaySession>()
    @Volatile private var attachment: AirPlayAttachment? = null
    private var releasingOwner: AirPlayListenerIdentity? = null
    private var serverSocket: ServerSocket? = null
    private var additionalServers: List<ServerSocket> = emptyList()
    private var bridge: Ipv6NcmBridge? = null
    private var tun: ParcelFileDescriptor? = null
    private var attachGeneration = 0

    override fun onBind(intent: Intent?): IBinder = binder

    @Synchronized
    fun attach(
        ncm: NcmUsbBridge,
        linkLocal: String,
        hostMac: ByteArray,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
    ): AttachResult {
        if (destroyed) return AttachResult.Failed("AirPlay service has been destroyed")
        if (hasRetainedResources()) {
            scheduleRelease()
            return AttachResult.Failed("Previous AirPlay resources are still being released")
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            val address = InetAddress.getByName(linkLocal)
            if (address !is Inet6Address || !address.isLinkLocalAddress) {
                throw IllegalArgumentException("linkLocal must be a link-local IPv6 literal")
            }
            require(hostMac.size == 6) { "hostMac must be 6 bytes" }

            val tunFd = Builder()
                .addAddress(linkLocal, LINK_PREFIX)
                .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                .setSession(SESSION_NAME)
                .setMtu(TUN_MTU)
                .setBlocking(true)
                // An empty app list routes every UID through this VPN. Scope it before establish;
                // rejection must reach the existing attachment cleanup, never an unscoped retry.
                .addAllowedApplication(packageName)
                .establish()
                ?: throw IOException("VpnService.establish returned null")
            tun = tunFd

            val ipv6Bridge = Ipv6NcmBridge(ncm, tunFd, hostMac) { error ->
                onTransportError(generation, listener, error)
            }
            ipv6Bridge.start()
            bridge = ipv6Bridge

            startAirPlayServer(
                generation,
                AirPlayAttachment(address, config, identity, pairings, mfi, listener, media),
            )
            AttachResult.Started
        } catch (error: Exception) {
            revokeAdmission()
            scheduleRelease()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Starts the AirPlay listener on the local-only Wi-Fi AP address without establishing a VPN or
     * NCM bridge.
     */
    @Synchronized
    fun attachWireless(
        bindAddress: InetAddress,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        additionalBindAddresses: List<InetAddress> = emptyList(),
        listenerIdentity: AirPlayListenerIdentity? = null,
    ): AttachResult {
        if (destroyed) return AttachResult.Failed("AirPlay service has been destroyed")
        if (hasRetainedResources()) {
            scheduleRelease()
            return AttachResult.Failed("Previous AirPlay resources are still being released")
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            startAirPlayServer(
                generation,
                AirPlayAttachment(bindAddress, config, identity, pairings, mfi, listener, media,
                    additionalBindAddresses, listenerIdentity),
            )
            AttachResult.Started
        } catch (error: Exception) {
            revokeAdmission()
            scheduleRelease()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Releases the active AirPlay listener and whichever VPN/NCM transport resources are active. */
    fun detach() {
        releaseResources()
    }

    fun detachWireless(owner: AirPlayListenerIdentity) {
        val owns = synchronized(this) {
            (attachment?.listenerIdentity === owner || releasingOwner === owner).also {
                if (it) revokeAdmission()
            }
        }
        if (owns) releaseResources()
    }

    fun isAttached(): Boolean = active.get() && attachment != null

    /** Port the AirPlay listener actually bound, which may differ from the configured port. */
    fun boundPort(): Int? = attachment?.config?.port

    /** Discovery must advertise only address families whose listener actually bound. */
    fun boundAddresses(): List<InetAddress> = attachment?.let {
        listOf(it.address) + it.additionalAddresses
    }.orEmpty()

    override fun onDestroy() {
        destroyed = true
        // Revoke admission now. Session/native close waits belong to the release worker,
        // never the Android main thread or this service's monitor.
        revokeAdmission()
        scheduleRelease()
        super.onDestroy()
    }

    private fun startAirPlayServer(
        generation: Int,
        replacement: AirPlayAttachment,
    ) {
        val servers = AirPlayPortSelector.bindWithOptionalAddresses(
            primaryAddress = replacement.address,
            additionalAddresses = replacement.additionalAddresses,
            preferredPort = replacement.config.port,
            onFallback = { busy, bound ->
                Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
            },
            onOptionalFailure = { address, error ->
                runCatching { replacement.listener.onDebugLog(
                    "airplay optional listener unavailable family=${if (address is Inet6Address) "IPv6" else "IPv4"} " +
                        "failureClass=${error.javaClass.simpleName}",
                ) }
            },
        )
        val server = servers.first()
        attachment = replacement.copy(config = replacement.config.copy(port = server.localPort),
            additionalAddresses = servers.drop(1).map { it.inetAddress })
        serverSocket = server
        additionalServers = servers.drop(1)
        servers.forEach { bound ->
            runCatching { replacement.listener.onDebugLog(
                "airplay listener ready family=${if (bound.inetAddress is Inet6Address) "IPv6" else "IPv4"} port=${bound.localPort}",
            ) }
            Thread(
                { acceptLoop(generation, bound) },
                "airplay-accept",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun acceptLoop(
        generation: Int,
        server: ServerSocket,
    ) {
        try {
            while (active.get()) {
                val socket: Socket = server.accept()
                val acceptedAtNanos = System.nanoTime()
                Log.i(TAG, "airplay connection accepted from ${socket.remoteSocketAddress}")
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.setSoLinger(true, 0)
                val session = synchronized(this) {
                    if (!active.get() || generation != attachGeneration ||
                        (serverSocket !== server && additionalServers.none { it === server })) {
                        socket.close()
                        return
                    }
                    val current = attachment
                    if (current == null) {
                        socket.close()
                        return
                    }
                    val internalPeer = isInternalAirPlayPeer(socket.inetAddress, socket.localAddress)
                    current.listenerIdentity?.let { owner ->
                        current.listener.onTcpAccepted(AirPlayTcpAccepted(
                            owner, internalPeer, acceptedAtNanos,
                        ))
                    }
                    runCatching { current.listener.onDebugLog(
                        "airplay TCP accepted family=${if (socket.inetAddress is Inet6Address) "IPv6" else "IPv4"}",
                    ) }
                    AirPlaySession(
                        socket = socket,
                        config = current.config,
                        identity = current.identity,
                        pairings = current.pairings,
                        mfi = current.mfi,
                        listener = object : AirPlaySessionListener by current.listener {
                            // These events drive background startup without a foreground Surface.
                            // Keep their forwarding explicit across listener-interface changes.
                            override fun onMainScreenReady(session: AirPlaySession) {
                                current.listener.onMainScreenReady(session)
                            }

                            override fun onMainScreenVideoReceived(session: AirPlaySession) {
                                current.listener.onMainScreenVideoReceived(session)
                            }

                            override fun onWirelessRuntimeReady(session: AirPlaySession) {
                                current.listener.onWirelessRuntimeReady(session)
                            }

                            override fun onWirelessRuntimeUnavailable(session: AirPlaySession) {
                                current.listener.onWirelessRuntimeUnavailable(session)
                            }

                            override fun onNavigationDataReceived(session: AirPlaySession) {
                                current.listener.onNavigationDataReceived(session)
                            }

                            override fun onClusterContentProgress(session: AirPlaySession, progress: ClusterContentProgress) {
                                current.listener.onClusterContentProgress(session, progress)
                            }

                            override fun onSessionActive(session: AirPlaySession) {
                                if (current.listenerIdentity == null || !internalPeer) current.listener.onSessionActive(session)
                            }

                            override fun onSessionDisconnected(session: AirPlaySession) {
                                if (current.listenerIdentity == null || !internalPeer) current.listener.onSessionDisconnected(session)
                            }

                            override fun onRemoteControlMessage(
                                session: AirPlaySession,
                                streamId: Long,
                                message: Map<String, Any?>,
                            ) {
                                current.listener.onRemoteControlMessage(session, streamId, message)
                            }

                            override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
                                current.listener.onVideoPlaybackUiRequested(session)
                            }

                            override fun onSessionEnded(session: AirPlaySession) {
                                removeSession(session)
                                if (current.listenerIdentity == null || !internalPeer) current.listener.onSessionEnded(session)
                            }
                        },
                        media = current.media,
                    ).also(::addSession)
                }
                session.start()
            }
        } catch (error: IOException) {
            if (active.get()) {
                attachment?.listener?.let { onTransportError(generation, it, error) }
            }
        }
    }

    private fun addSession(session: AirPlaySession) {
        synchronized(sessionsLock) { sessions.add(session) }
    }

    private fun removeSession(session: AirPlaySession?) {
        if (session == null) return
        synchronized(sessionsLock) { sessions.remove(session) }
    }

    private fun closeSessionsLocked() {
        // Do not wait while holding sessionsLock: another closing session removes itself there.
        // Retain a second reference until awaitClosed confirms media teardown, including when
        // onSessionEnded removes the session just before its completion latch is signalled.
        val pending = synchronized(sessionsLock) {
            closingSessions.addAll(sessions)
            closingSessions.toList()
        }
        pending.forEach { it.close() }
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(SESSION_CLOSE_TIMEOUT_MILLIS)
        pending.forEach { session ->
            val remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)
            if (!session.awaitClosed(remaining)) throw IOException("AirPlay media cleanup cancellation pending")
            synchronized(sessionsLock) {
                closingSessions.remove(session)
                sessions.remove(session)
            }
        }
    }

    private fun hasRetainedResources(): Boolean = releaseInProgress || active.get() || attachment != null ||
        serverSocket != null || additionalServers.isNotEmpty() || bridge != null || tun != null ||
        synchronized(sessionsLock) { sessions.isNotEmpty() || closingSessions.isNotEmpty() }

    private fun onTransportError(
        generation: Int,
        listener: AirPlaySessionListener,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        Log.e(TAG, "CarPlay transport stopped: $message", error)
        Thread(
            {
                val releasedGeneration = synchronized(this) {
                    if (generation != attachGeneration) return@Thread
                    revokeAdmission()
                    attachGeneration
                }
                runCatching { releaseResources(releasedGeneration) }.onFailure {
                    Log.w(TAG, "AirPlay cleanup pending; attachment remains blocked", it)
                }
                listener.onTransportError(message)
                synchronized(this) {
                    if (attachGeneration == releasedGeneration && !active.get()) stopSelf()
                }
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun scheduleRelease() {
        val startWorker = synchronized(this) {
            revokeAdmission()
            releaseRequestedGeneration = attachGeneration
            releaseScheduled.compareAndSet(false, true)
        }
        if (!startWorker) return
        Thread({
            while (true) {
                val expected = synchronized(this) { releaseRequestedGeneration }
                try { releaseResources(expected) }
                catch (error: Exception) { Log.w(TAG, "AirPlay cleanup pending; resource barrier retained", error) }
                val complete = synchronized(this) {
                    if (releaseRequestedGeneration == expected) {
                        releaseScheduled.set(false)
                        true
                    } else false
                }
                if (complete) break
            }
        }, "airplay-service-release").apply { isDaemon = true; start() }
    }

    @Synchronized private fun revokeAdmission() {
        if (active.getAndSet(false) || attachment != null) attachGeneration += 1
        attachment?.let { releasingOwner = it.listenerIdentity }
        attachment = null
        releaseInProgress = true
    }

    /** Holds only the cleanup owner lock during waits, so lifecycle/accept callbacks stay responsive. */
    private fun releaseResources(expectedGeneration: Int? = null) = synchronized(releaseLock) release@{
        synchronized(this) {
            // An already-completed old release must never revoke a fresh attachment.
            if (expectedGeneration != null && expectedGeneration != attachGeneration) return@release
            revokeAdmission()
        }
        val failures = mutableListOf<Exception>()
        fun release(block: () -> Unit): Boolean = try {
            block()
            true
        } catch (error: Exception) {
            failures.add(error)
            false
        }
        val primary = synchronized(this) { serverSocket }
        if (release { primary?.close() }) synchronized(this) { if (serverSocket === primary) serverSocket = null }
        val additional = synchronized(this) { additionalServers }
        val retained = additional.filterNot { server -> release { server.close() } }
        synchronized(this) { additionalServers = retained }
        release { closeSessionsLocked() }
        val oldBridge = synchronized(this) { bridge }
        if (release { oldBridge?.close() }) synchronized(this) { if (bridge === oldBridge) bridge = null }
        val oldTun = synchronized(this) { tun }
        if (release { oldTun?.close() }) synchronized(this) { if (tun === oldTun) tun = null }
        if (failures.isNotEmpty()) {
            throw IOException("AirPlay resources are not fully released", failures.first()).also { error ->
                failures.drop(1).forEach(error::addSuppressed)
            }
        }
        synchronized(this) {
            releasingOwner = null
            releaseInProgress = false
        }
    }

    companion object {
        private const val SESSION_CLOSE_TIMEOUT_MILLIS = 4_000L
        private const val TAG = "xcertplay-usb"
        private const val LINK_PREFIX = 64
        private const val LINK_LOCAL_ROUTE = "fe80::"
        private const val SESSION_NAME = "xcertplay CarPlay"
        private const val TUN_MTU = 1500

        /** Returns the VPN consent intent, or null when consent is already granted. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)
    }
}
