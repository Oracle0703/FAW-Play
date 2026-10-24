package com.shilapi.xcertplay.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

data class CarPlayBonjourEndpoint(
    val serviceName: String,
    val host: String,
    val port: Int,
    val bluetoothId: String?,
)

internal fun normalizedCarPlayBluetoothIdentity(value: String?): String? = value
    ?.replace(":", "")?.replace("-", "")?.uppercase(java.util.Locale.US)
    ?.takeIf { it.length == 12 && it.all { char -> char in '0'..'9' || char in 'A'..'F' } }

internal fun matchesCarPlayBluetoothIdentity(expected: String?, actual: String?): Boolean =
    expected == null || normalizedCarPlayBluetoothIdentity(expected)?.let {
        it == normalizedCarPlayBluetoothIdentity(actual)
    } == true

sealed interface CarPlayBonjourEvent {
    data class Discovery(val stage: Stage, val ipv4Count: Int = 0, val ipv6Count: Int = 0) : CarPlayBonjourEvent {
        enum class Stage { ADDED, NO_MATCHING_ADDRESS, INVALID_PORT }
    }
    data class Resolved(val endpoint: CarPlayBonjourEndpoint, val requestId: Int = 0) : CarPlayBonjourEvent
    data class EndpointsExhausted(val requestId: Int) : CarPlayBonjourEvent

    data class Probed(
        val endpoint: CarPlayBonjourEndpoint,
        val attempts: Int,
        val statusLine: String?,
        val error: IOException?,
        val requestId: Int = 0,
    ) : CarPlayBonjourEvent

    data class ProbeProgress(val stage: Stage, val attempt: Int, val ipv6: Boolean) : CarPlayBonjourEvent {
        enum class Stage { CONNECTING, TCP_CONNECTED, REQUEST_SENT }
    }
    data class ProbeFailed(val stage: ProbeProgress.Stage, val attempt: Int, val error: IOException) : CarPlayBonjourEvent
}

/** Saved reports need discovery outcomes without phone names, addresses, or pairing identifiers. */
fun CarPlayBonjourEvent.diagnosticSummary(): String = when (this) {
    is CarPlayBonjourEvent.Discovery -> "control discovery stage=$stage ipv4=$ipv4Count ipv6=$ipv6Count"
    is CarPlayBonjourEvent.Resolved ->
        "control resolved family=${if (':' in endpoint.host) "IPv6" else "IPv4"} port=${endpoint.port}"
    is CarPlayBonjourEvent.EndpointsExhausted -> "control endpoints exhausted request=${requestId}"
    is CarPlayBonjourEvent.Probed -> {
        val status = statusLine?.let { Regex("^HTTP/\\d(?:\\.\\d)? (\\d{3})(?: |$)").find(it)?.groupValues?.get(1) }
        "control probe attempts=$attempts status=${status ?: "none"} error=${error?.javaClass?.simpleName ?: "none"}"
    }
    is CarPlayBonjourEvent.ProbeProgress ->
        "control probe stage=$stage attempt=$attempt family=${if (ipv6) "IPv6" else "IPv4"}"
    is CarPlayBonjourEvent.ProbeFailed ->
        "control probe failed after=$stage attempt=$attempt failureClass=${error.javaClass.simpleName}"
}

/** Pure protocol values shared by the Android runtime and JVM tests. */
object CarPlayBonjourProtocol {
    internal fun featuresTxt(features: Long): String {
        val low = "0x${(features and 0xffffffffL).toString(16)}"
        val high = features ushr 32
        return if (high == 0L) low else "$low,0x${high.toString(16)}"
    }

    fun airPlayTxtRecords(
        config: AirPlayConfig,
        identity: AirPlayIdentity,
    ): Map<String, String> = linkedMapOf(
        "deviceid" to config.deviceId,
        "features" to featuresTxt(AirPlayInfoPlist.features(config)),
        "flags" to "0x4",
        "model" to config.model,
        "srcvers" to config.sourceVersion,
        "protovers" to "1.1",
        "pi" to identity.pairingId,
        "pk" to identity.publicKeyHex,
    )

    fun connectProbeRequest(
        host: String,
        port: Int,
        sourceVersion: String,
        deviceId: String,
    ): String {
        val unbracketedHost = host.removeSurrounding("[", "]").substringBefore('%')
        require(unbracketedHost.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port must be in 1..65535" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion must not be empty" }
        require('\r' !in sourceVersion && '\n' !in sourceVersion) {
            "sourceVersion must not contain a line break"
        }
        require('\r' !in unbracketedHost && '\n' !in unbracketedHost) {
            "host must not contain a line break"
        }
        val receiverDeviceId = deviceId.replace(":", "")
        require(receiverDeviceId.isNotEmpty()) { "deviceId must contain a hexadecimal value" }
        require('\r' !in receiverDeviceId && '\n' !in receiverDeviceId) {
            "deviceId must not contain a line break"
        }
        val hostHeader = if (':' in unbracketedHost) {
            "[$unbracketedHost]:$port"
        } else {
            "$unbracketedHost:$port"
        }
        return "GET /ctrl-int/1/connect HTTP/1.1\r\n" +
            "Host: $hostHeader\r\n" +
            "User-Agent: AirPlay/$sourceVersion\r\n" +
            "AirPlay-Receiver-Device-ID: $receiverDeviceId\r\n" +
            "Connection: close\r\n" +
            "\r\n"
    }
}

/**
 * Publishes the accessory AirPlay service and discovers the iPhone's CarPlay control service.
 *
 * Discovery callbacks only enqueue work. Resolution, probing, and [onEvent] run off the caller
 * thread. Interface discovery gives each family its own worker so failed probes cannot hold up
 * the other family's connection.
 */
class CarPlayBonjour(
    context: Context,
    private val config: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val advertisedHost: String? = null,
    private val useInterfaceMdns: Boolean = false,
    private val onEvent: (CarPlayBonjourEvent) -> Unit = {},
    additionalAddresses: List<InetAddress> = emptyList(),
    private val createSocket: () -> Socket = { Socket() },
    private val diagnostic: (String) -> Unit = {},
    private val createMdns: (InetAddress, String) -> JmDNS = { address, name -> JmDNS.create(address, name) },
    targetBluetoothId: String? = null,
) : Closeable {
    private class InterfaceService(
        val endpoint: CarPlayBonjourEndpoint,
        val address: InetAddress,
        val requestId: Int,
        val dns: JmDNS,
        val budget: CarPlayControlProbeBudget.Attempts,
    ) {
        var result: CarPlayBonjourEvent.Probed? = null
        val serviceKey = endpoint.serviceName to (address is Inet6Address)
        val endpointKey = CarPlayControlEndpointKey(endpoint.serviceName, endpoint.host, endpoint.port,
            address is Inet6Address)
        // Accessed only under lifecycleLock; replacing an endpoint cancels its pending read.
        var socket: Socket? = null
    }
    private val diagnosticId = diagnosticIds.incrementAndGet()
    private val nsdManager = (context.applicationContext ?: context)
        .getSystemService(Context.NSD_SERVICE) as NsdManager
    private val services = LinkedBlockingQueue<NsdServiceInfo>()
    private val interfaceServices = LinkedBlockingQueue<InterfaceService>()
    private val ipv6InterfaceServices = LinkedBlockingQueue<InterfaceService>()
    private val probeRequestId = AtomicInteger()
    @Volatile private var reconnecting = false
    private var exhaustedRequest: Int? = null
    @Volatile private var controlProbesEnabled = true
    @Volatile private var currentTargetBluetoothId: String? = targetBluetoothId
    private val discoveryEvents = LinkedBlockingQueue<CarPlayBonjourEvent.Discovery>(32)
    private val seenServices = ConcurrentHashMap.newKeySet<String>()
    private val currentInterfaceServices = mutableMapOf<Pair<String, Boolean>, InterfaceService>()
    private val interfaceProbeBudgets = CarPlayControlProbeBudget()
    private val lifecycleLock = Object()
    private val closeLock = Any()
    private var closeCompleted = false
    private val localAdvertisedAddress = advertisedHostAddress()
    private val advertisedAddresses = (listOfNotNull(localAdvertisedAddress) + additionalAddresses).distinct()
    @Volatile private var publishedFamilies = "none"
    private val addedCount = AtomicInteger()
    private val resolvedCount = AtomicInteger()
    private val addressMismatchCount = AtomicInteger()
    private val probeCount = AtomicInteger()
    private val successfulProbeCount = AtomicInteger()
    private val browseRefreshCount = AtomicInteger()
    private val lastProbe = AtomicReference("not_started")

    /** Includes zero counts so a silent discovery interval is visible in exported reports. */
    fun diagnosticSnapshot(): String =
        "bonjourAdded=${addedCount.get()} bonjourResolved=${resolvedCount.get()} " +
            "bonjourAddressMismatch=${addressMismatchCount.get()} connectProbes=${probeCount.get()} " +
            "connectProbe2xx=${successfulProbeCount.get()} lastProbe=${lastProbe.get()} " +
            "mdnsFamilies=$publishedFamilies bonjourBrowseRefreshes=${browseRefreshCount.get()}"
    private val multicastLock = (context.applicationContext ?: context)
        .getSystemService(WifiManager::class.java)
        .createMulticastLock("carplay-bonjour").apply { setReferenceCounted(false) }

    private var started = false
    @Volatile
    private var closed = false
    private var registrationRequested = false
    private var discoveryRequested = false
    @Volatile
    private var worker: Thread? = null
    @Volatile
    private var ipv6Worker: Thread? = null
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val interfaceMdns = mutableListOf<JmDNS>()
    private val interfaceBrowsers = mutableMapOf<JmDNS, CarPlayMdnsBrowse>()
    // Re-adding the business listener would replay cached events into connection handling.
    // This stable no-op listener only asks JmDNS's public API to start another browse round.
    private val browseRefreshListener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) = Unit
        override fun serviceRemoved(event: ServiceEvent) = Unit
        override fun serviceResolved(event: ServiceEvent) = Unit
    }

    private val interfaceListener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            if (!closed && controlProbesEnabled) {
                discoveryEvents.offer(CarPlayBonjourEvent.Discovery(CarPlayBonjourEvent.Discovery.Stage.ADDED))
                event.dns.requestServiceInfo(event.type, event.name, true)
            }
        }

        override fun serviceRemoved(event: ServiceEvent) {
            synchronized(lifecycleLock) {
                val service = currentInterfaceServices.remove(event.name to (event.dns.inetAddress is Inet6Address))
                service?.socket?.let { runCatching { it.close() } }
                if (service != null && isCurrentProbe(service.requestId)) {
                    interfaceBrowsers[event.dns]?.resume(RECOVERY_BROWSE_ROUNDS)
                }
                lifecycleLock.notifyAll()
            }
        }

        override fun serviceResolved(event: ServiceEvent) {
            if (closed || !controlProbesEnabled) return
            val info = event.info
            // Use the registry address, not deprecated getInterface(), which can return
            // another address family of the same Android interface.
            val address = info.inetAddresses.firstOrNull {
                (it is Inet4Address) == (event.dns.inetAddress is Inet4Address)
            }?.let(::applyLocalScope)
            if (address == null || info.port !in 1..65535) {
                discoveryEvents.offer(CarPlayBonjourEvent.Discovery(
                    if (address == null) CarPlayBonjourEvent.Discovery.Stage.NO_MATCHING_ADDRESS
                    else CarPlayBonjourEvent.Discovery.Stage.INVALID_PORT,
                    info.inetAddresses.count { it is Inet4Address }, info.inetAddresses.count { it is Inet6Address },
                ))
                return
            }
            val endpoint = CarPlayBonjourEndpoint(
                event.name, address.hostAddress ?: return, info.port,
                info.getPropertyString("id"),
            )
            // The control TXT id is the Bluetooth identity selected for this connection.
            // Never ask another phone on a shared network to start CarPlay.
            synchronized(lifecycleLock) {
                if (closed || !controlProbesEnabled ||
                    !matchesCarPlayBluetoothIdentity(currentTargetBluetoothId, endpoint.bluetoothId)) return
                val serviceKey = event.name to (address is Inet6Address)
                val previous = currentInterfaceServices[serviceKey]
                val endpointKey = CarPlayControlEndpointKey(event.name, endpoint.host, endpoint.port,
                    address is Inet6Address)
                if (previous?.endpointKey == endpointKey) return
                // An IP or port update invalidates the old result without cancelling the other family.
                previous?.socket?.let { runCatching { it.close() } }
                currentInterfaceServices.remove(serviceKey)
                val budget = interfaceProbeBudgets.forEndpoint(endpointKey,
                    if (reconnecting) 1 else MAX_PROBE_ATTEMPTS)
                if (budget == null) {
                    diagnostic("control endpoint tracking limit reached")
                    interfaceBrowsers[event.dns]?.resume(RECOVERY_BROWSE_ROUNDS)
                    return
                }
                val service = InterfaceService(endpoint, address, probeRequestId.get(), event.dns, budget)
                currentInterfaceServices[serviceKey] = service
                if (budget.available()) {
                    interfaceBrowsers[event.dns]?.stop()
                    (if (address is Inet6Address) ipv6InterfaceServices else interfaceServices).offer(service)
                    lifecycleLock.notifyAll()
                } else {
                    interfaceBrowsers[event.dns]?.resume(RECOVERY_BROWSE_ROUNDS)
                }
            }
        }
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "AirPlay NSD registration failed code=$errorCode")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "AirPlay NSD unregistration failed code=$errorCode")
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "CarPlay control discovery failed code=$errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "CarPlay control discovery stop failed code=$errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (closed) return
            val name = serviceInfo.serviceName ?: return
            val type = serviceInfo.serviceType ?: CARPLAY_CONTROL_SERVICE_TYPE
            val key = "$type|$name"
            if (!seenServices.add(key)) return
            services.offer(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val name = serviceInfo.serviceName ?: return
            val type = serviceInfo.serviceType ?: CARPLAY_CONTROL_SERVICE_TYPE
            seenServices.remove("$type|$name")
        }
    }

    /** Starts publication and discovery. Calling this more than once is harmless. */
    fun start() {
        synchronized(lifecycleLock) {
            check(!closed) { "CarPlayBonjour is closed" }
            if (started) return
            started = true
            try {
                multicastLock.acquire()
                if (useInterfaceMdns) {
                    requireNotNull(localAdvertisedAddress) {
                        "Interface mDNS requires a local advertised address"
                    }
                    // A JmDNS instance joins only its address family's multicast group.
                    val publishedAddresses = mutableListOf<InetAddress>()
                    for ((index, address) in advertisedAddresses.withIndex()) {
                        var dns: JmDNS? = null
                        try {
                            dns = createMdns(address, "carplay-${config.deviceId.replace(":", "")}")
                            val registry = dns
                            interfaceBrowsers[registry] = CarPlayMdnsBrowse(refresh = {
                                registry.addServiceListener("$CARPLAY_CONTROL_SERVICE_TYPE.local.", browseRefreshListener)
                                browseRefreshCount.incrementAndGet()
                            })
                            dns.addServiceListener("$CARPLAY_CONTROL_SERVICE_TYPE.local.", interfaceListener)
                            dns.registerService(ServiceInfo.create(
                                "$AIRPLAY_SERVICE_TYPE.local.", config.deviceName, config.port,
                                0, 0, CarPlayBonjourProtocol.airPlayTxtRecords(config, identity),
                            ))
                            interfaceMdns.add(dns)
                            publishedAddresses.add(address)
                        } catch (error: Exception) {
                            dns?.let { interfaceBrowsers.remove(it)?.stop() }
                            dns?.let { if (runCatching { it.close() }.isFailure) interfaceMdns.add(it) }
                            if (index == 0) throw error
                            Log.w(TAG, "Optional mDNS family unavailable: ${error.javaClass.simpleName}")
                        }
                    }
                    publishedFamilies = publishedAddresses.joinToString(",") {
                        if (it is Inet4Address) "IPv4" else "IPv6"
                    }
                    if (publishedAddresses.any { it is Inet6Address }) {
                        // IPv6 discovery may arrive first. Its bounded but lengthy probe retries
                        // must not delay the existing IPv4 control path behind a second family.
                        ipv6Worker = Thread({ runInterfaceWorker(ipv6InterfaceServices, ipv6 = true) }, "$WORKER_NAME-ipv6")
                            .apply { isDaemon = true; start() }
                    }
                } else {
                    registerAirPlay()
                    registrationRequested = true
                    nsdManager.discoverServices(
                        CARPLAY_CONTROL_SERVICE_TYPE,
                        NsdManager.PROTOCOL_DNS_SD,
                        discoveryListener,
                    )
                    discoveryRequested = true
                }
                worker = Thread(::runWorker, WORKER_NAME).apply {
                    isDaemon = true
                    start()
                }
            } catch (error: Exception) {
                closed = true
                if (registrationRequested) {
                    if (runCatching { nsdManager.unregisterService(registrationListener) }.isSuccess) registrationRequested = false
                }
                if (discoveryRequested) {
                    if (runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }.isSuccess) discoveryRequested = false
                }
                worker?.interrupt()
                ipv6Worker?.interrupt()
                interfaceBrowsers.values.forEach { it.stop() }
                interfaceBrowsers.clear()
                activeSockets.removeAll { socket -> runCatching { socket.close() }.isSuccess }
                interfaceMdns.removeAll { dns -> runCatching { dns.close() }.isSuccess }
                publishedFamilies = "none"
                if (multicastLock.isHeld) runCatching { multicastLock.release() }.onFailure(error::addSuppressed)
                throw error
            }
        }
    }

    /**
     * Re-browse the current registries without replacing the AP, listener or advertised identity.
     * JmDNS owns DNS TTLs; no endpoint address is kept in an application-level reconnect cache.
     * Replaying its live service cache is intentional for this explicit recovery request.
     */
    fun requestReconnect(bluetoothId: String, onRequest: (Int) -> Unit = {}): Int = synchronized(lifecycleLock) {
        check(started && !closed && useInterfaceMdns) { "Interface discovery is not active" }
        require(normalizedCarPlayBluetoothIdentity(bluetoothId) != null)
        currentTargetBluetoothId = bluetoothId
        reconnecting = true
        controlProbesEnabled = true
        val request = probeRequestId.incrementAndGet()
        exhaustedRequest = null
        lifecycleLock.notifyAll()
        onRequest(request)
        interfaceServices.clear()
        ipv6InterfaceServices.clear()
        seenServices.clear()
        currentInterfaceServices.clear()
        interfaceProbeBudgets.clear()
        activeSockets.forEach { runCatching { it.close() } }
        interfaceMdns.forEach { dns ->
            interfaceBrowsers.remove(dns)?.stop()
            interfaceBrowsers[dns] = CarPlayMdnsBrowse(refresh = {
                dns.addServiceListener("$CARPLAY_CONTROL_SERVICE_TYPE.local.", browseRefreshListener)
                browseRefreshCount.incrementAndGet()
            })
            dns.removeServiceListener("$CARPLAY_CONTROL_SERVICE_TYPE.local.", interfaceListener)
            dns.addServiceListener("$CARPLAY_CONTROL_SERVICE_TYPE.local.", interfaceListener)
        }
        request
    }

    /** Cancels only this discovery request; a late old result cannot cancel a newer one. */
    fun finishReconnect(requestId: Int) = synchronized(lifecycleLock) {
        if (probeRequestId.get() != requestId || !reconnecting) return@synchronized
        stopControlDiscovery()
    }

    /** Capture without acquiring lifecycleLock; a delayed completion cannot stop a newer request. */
    fun captureControlDiscoveryStop(): (() -> Unit)? {
        val requestId = probeRequestId.get()
        if (!isCurrentProbe(requestId)) return null
        return {
            synchronized(lifecycleLock) {
                if (isCurrentProbe(requestId)) stopControlDiscovery()
            }
        }
    }

    /** A working session needs no more connect requests; explicit reconnect can reuse the registries. */
    fun stopControlDiscovery() = synchronized(lifecycleLock) {
        reconnecting = false
        controlProbesEnabled = false
        probeRequestId.incrementAndGet()
        interfaceServices.clear()
        ipv6InterfaceServices.clear()
        currentInterfaceServices.clear()
        interfaceProbeBudgets.clear()
        interfaceBrowsers.values.forEach { it.stop() }
        activeSockets.forEach { runCatching { it.close() } }
    }

    override fun close() = synchronized(closeLock) {
        if (closeCompleted) return@synchronized
        val closeStarted = System.nanoTime()
        val workersToJoin: List<Thread>
        val dnsToClose: List<JmDNS>
        val failures = mutableListOf<Exception>()
        fun release(block: () -> Unit): Boolean = try {
            block()
            true
        } catch (error: Exception) {
            failures.add(error)
            false
        }
        synchronized(lifecycleLock) {
            closed = true
            currentInterfaceServices.clear()
            interfaceProbeBudgets.clear()
            interfaceBrowsers.values.forEach { it.stop() }
            interfaceBrowsers.clear()
            if (registrationRequested) {
                if (release { nsdManager.unregisterService(registrationListener) }) registrationRequested = false
            }
            if (discoveryRequested) {
                if (release { nsdManager.stopServiceDiscovery(discoveryListener) }) discoveryRequested = false
            }
            activeSockets.removeAll { socket -> release { socket.close() } }
            services.clear()
            interfaceServices.clear()
            ipv6InterfaceServices.clear()
            discoveryEvents.clear()
            dnsToClose = interfaceMdns.toList()
            publishedFamilies = "none"
            workersToJoin = listOfNotNull(worker, ipv6Worker)
            workersToJoin.forEach { it.interrupt() }
            if (multicastLock.isHeld) release { multicastLock.release() }
        }
        val registryFailures = arrayOfNulls<Throwable>(dnsToClose.size)
        fun closeRegistry(index: Int, dns: JmDNS) {
            val started = System.nanoTime()
            var released = false
            try {
                dns.close()
                released = true
            } catch (error: Throwable) {
                // A failure on a close thread must reach the caller, including fatal errors.
                registryFailures[index] = error
            }
            runCatching { diagnostic("Bonjour close instance=$diagnosticId registry=$index " +
                "family=${if (dns.inetAddress is Inet4Address) "IPv4" else "IPv6"} " +
                "released=$released elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)}") }
            if (released) synchronized(lifecycleLock) { interfaceMdns.remove(dns) }
        }
        if (dnsToClose.size < 2) {
            dnsToClose.forEachIndexed(::closeRegistry)
        } else {
            // Each registry owns a different family's multicast socket and cancellation state.
            // Their goodbye/cancellation waits can overlap; the next stack still waits for all
            // closes to return and their threads to exit. closeLock also prevents a second
            // close() from starting another close for these same instances.
            val closers = dnsToClose.mapIndexed { index, dns ->
                Thread({ closeRegistry(index, dns) }, "$WORKER_NAME-close-$diagnosticId-$index")
                    .apply { isDaemon = true }
            }
            closers.forEachIndexed { index, closer ->
                try { closer.start() }
                catch (error: Throwable) { registryFailures[index] = error }
            }
            var interrupted = false
            closers.forEach { closer ->
                while (closer.isAlive) {
                    try { closer.join() }
                    catch (_: InterruptedException) { interrupted = true }
                }
            }
            // Interrupting this waiter cannot abandon a live registry or let a retry overlap it.
            if (interrupted) Thread.currentThread().interrupt()
        }
        registryFailures.filterNotNull().forEach { error ->
            if (error is Exception) failures.add(error) else throw error
        }
        var selfPending = false
        workersToJoin.forEach { current ->
            if (current === Thread.currentThread()) {
                selfPending = true
                failures.add(IOException("Bonjour callback worker is still running"))
            }
            else if (release { joinWorker(current) }) synchronized(lifecycleLock) {
                if (worker === current) worker = null
                if (ipv6Worker === current) ipv6Worker = null
            }
        }
        runCatching { diagnostic("Bonjour close instance=$diagnosticId released=${failures.isEmpty() && !selfPending} " +
            "registries=${dnsToClose.size} elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStarted)}") }
        if (failures.isNotEmpty()) {
            throw IOException("Bonjour resources are not fully released", failures.first()).also { error ->
                failures.drop(1).forEach(error::addSuppressed)
            }
        }
        // A callback may revoke its own Bonjour instance. It can request shutdown, but only
        // another thread can confirm that this callback worker has subsequently exited.
        closeCompleted = !selfPending
    }

    @Suppress("DEPRECATION")
    private fun registerAirPlay() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = config.deviceName
            serviceType = AIRPLAY_SERVICE_TYPE
            port = config.port
            CarPlayBonjourProtocol.airPlayTxtRecords(config, identity).forEach { (key, value) ->
                setAttribute(key, value)
            }
            localAdvertisedAddress?.let(::setHost)
        }
        nsdManager.registerService(
            serviceInfo,
            NsdManager.PROTOCOL_DNS_SD,
            registrationListener,
        )
    }

    private fun advertisedHostAddress(): InetAddress? {
        val value = advertisedHost?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val address = try {
            InetAddress.getByName(value.removeSurrounding("[", "]"))
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid advertised host: $value", error)
        }
        require(!address.isLoopbackAddress) {
            "advertisedHost must not be a loopback address"
        }
        require(address !is Inet6Address || address.isLinkLocalAddress) {
            "advertisedHost must be link-local IPv6 or IPv4"
        }
        return address
    }

    private fun runWorker() {
        if (useInterfaceMdns) {
            runInterfaceWorker(interfaceServices, ipv6 = false)
            return
        }
        while (!closed) {
            val service = try {
                services.poll(WORKER_POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return
            } ?: continue
            if (closed) return
            try {
                handleService(service)
            } catch (_: InterruptedException) {
                return
            } catch (error: Exception) {
                if (!closed) Log.w(TAG, "CarPlay control service handling failed", error)
            }
        }
    }

    private fun runInterfaceWorker(
        queue: LinkedBlockingQueue<InterfaceService>,
        ipv6: Boolean,
    ) {
        while (!closed) {
            try {
                synchronized(lifecycleLock) {
                    if (closed) return
                    interfaceBrowsers.forEach { (dns, browser) ->
                        if ((dns.inetAddress is Inet6Address) == ipv6) browser.refreshIfDue()
                    }
                }
                while (true) emit(discoveryEvents.poll() ?: break)
                exhaustedEndpoints()?.let(::emit)
                val service = queue.poll(WORKER_POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
                if (!isCurrentInterfaceService(service)) continue
                emit(CarPlayBonjourEvent.Resolved(service.endpoint, service.requestId))
                val result = probe(service.endpoint, service.address, service.requestId, service) ?: continue
                synchronized(lifecycleLock) {
                    if (isCurrentInterfaceService(service)) {
                        service.result = result
                        if (result.error != null) interfaceBrowsers[service.dns]?.resume(RECOVERY_BROWSE_ROUNDS)
                    }
                }
                if (isCurrentInterfaceService(service)) emit(result)
                exhaustedEndpoints()?.let(::emit)
            } catch (_: InterruptedException) {
                return
            } catch (error: Exception) {
                if (!closed) Log.w(TAG, "Interface CarPlay service handling failed", error)
            }
        }
    }

    private fun handleService(service: NsdServiceInfo) {
        val resolved = resolveWithRetry(service) ?: return
        val address = preferredAddress(resolved) ?: return
        val port = resolved.port
        if (port !in 1..65535) return
        val serviceName = resolved.serviceName ?: service.serviceName ?: return
        val host = address.hostAddress ?: return
        val bluetoothId = resolved.attributes
            ?.get("id")
            ?.let(::decodeTxtValue)
            ?.takeIf { it.isNotBlank() }
        val endpoint = CarPlayBonjourEndpoint(
            serviceName = serviceName,
            host = host,
            port = port,
            bluetoothId = bluetoothId,
        )
        if (!matchesCarPlayBluetoothIdentity(currentTargetBluetoothId, endpoint.bluetoothId)) return
        emit(CarPlayBonjourEvent.Resolved(endpoint))
        probe(endpoint, address)?.let(::emit)
    }

    @Suppress("DEPRECATION")
    private fun resolveWithRetry(service: NsdServiceInfo): NsdServiceInfo? {
        repeat(RESOLVE_ATTEMPTS) { attempt ->
            if (closed) return null
            val latch = CountDownLatch(1)
            val resolved = AtomicReference<NsdServiceInfo?>()
            val failure = AtomicInteger(FAILURE_NONE)
            val listener = object : NsdManager.ResolveListener {
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    resolved.set(serviceInfo)
                    latch.countDown()
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    failure.set(errorCode)
                    latch.countDown()
                }
            }
            val submitted = try {
                synchronized(lifecycleLock) {
                    if (closed) {
                        false
                    } else {
                        nsdManager.resolveService(service, listener)
                        true
                    }
                }
            } catch (error: RuntimeException) {
                Log.w(TAG, "CarPlay control service resolution failed", error)
                false
            }
            if (!submitted) return null
            val completed = try {
                latch.await(RESOLVE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                throw error
            }
            if (!completed) {
                Log.w(TAG, "CarPlay control service resolution timed out")
                return null
            }
            resolved.get()?.let { return it }
            if (failure.get() != NsdManager.FAILURE_ALREADY_ACTIVE) {
                Log.w(TAG, "CarPlay control service resolution failed code=${failure.get()}")
                return null
            }
            if (!closed && attempt + 1 < RESOLVE_ATTEMPTS) {
                Thread.sleep(RESOLVE_RETRY_DELAY_MILLIS)
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun preferredAddress(serviceInfo: NsdServiceInfo): InetAddress? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfo.hostAddresses.orEmpty()
        } else {
            listOfNotNull(serviceInfo.host)
        }
        return addresses.firstOrNull { it is Inet6Address && it.isLinkLocalAddress }
            ?.let(::applyLocalScope)
            ?: addresses.firstOrNull { it is Inet4Address }
            ?: addresses.firstOrNull { it is Inet6Address }
            ?: addresses.firstOrNull()
    }

    private fun applyLocalScope(address: InetAddress): InetAddress {
        val scope = advertisedAddresses.filterIsInstance<Inet6Address>()
            .firstOrNull { it.scopeId != 0 }?.scopeId ?: return address
        if (address !is Inet6Address || address.scopeId != 0) return address
        return try {
            Inet6Address.getByAddress(null, address.address, scope)
        } catch (_: Exception) {
            address
        }
    }

    private fun probe(
        endpoint: CarPlayBonjourEndpoint,
        address: InetAddress,
        requestId: Int = probeRequestId.get(),
        service: InterfaceService? = null,
    ): CarPlayBonjourEvent.Probed? {
        var lastError: IOException? = null
        val maxAttempts = service?.budget?.limit ?: if (reconnecting) 1 else MAX_PROBE_ATTEMPTS
        var attempts = 0
        while (attempts < maxAttempts) {
            if (!isCurrentProbe(requestId) || service != null && !isCurrentInterfaceService(service)) return null
            attempts = if (service == null) attempts + 1 else synchronized(lifecycleLock) {
                if (isCurrentInterfaceService(service)) service.budget.take() else null
            } ?: return null
            try {
                val statusLine = probeOnce(address, endpoint.port, attempts, requestId, service)
                if (!isCurrentProbe(requestId) || service != null && !isCurrentInterfaceService(service)) return null
                return CarPlayBonjourEvent.Probed(
                    endpoint = endpoint,
                    attempts = attempts,
                    statusLine = statusLine,
                    error = null,
                    requestId = requestId,
                )
            } catch (error: IOException) {
                lastError = error
            } catch (error: RuntimeException) {
                lastError = IOException("AirPlay control probe failed", error)
            }
            if (!isCurrentProbe(requestId) || service != null && !isCurrentInterfaceService(service)) return null
            if (attempts < maxAttempts) {
                synchronized(lifecycleLock) {
                    if (isCurrentProbe(requestId) && (service == null || isCurrentInterfaceService(service)))
                        lifecycleLock.wait(probeRetryDelayMillis(attempts))
                }
            }
        }
        return CarPlayBonjourEvent.Probed(
            endpoint = endpoint,
            attempts = maxAttempts,
            statusLine = null,
            error = lastError,
            requestId = requestId,
        )
    }

    /** All address families retain their chance, including finite re-browse after IO failure. */
    private fun exhaustedEndpoints(): CarPlayBonjourEvent.EndpointsExhausted? = synchronized(lifecycleLock) {
        val request = probeRequestId.get()
        if (!reconnecting || !isCurrentProbe(request) || exhaustedRequest == request) return@synchronized null
        val services = currentInterfaceServices.values.filter { it.requestId == request }
        if (services.isEmpty()) return@synchronized null
        val families = interfaceMdns.map { it.inetAddress is Inet6Address }.toSet()
        if (!families.all family@{ family ->
            val candidates = services.filter { (it.address is Inet6Address) == family }
            if (candidates.isEmpty()) return@family false
            candidates.all candidate@{ candidate ->
                val result = candidate.result ?: return@candidate false
                if (result.error != null) interfaceBrowsers[candidate.dns]?.exhausted() == true
                else result.statusLine?.let { !Regex("^HTTP/\\d(?:\\.\\d)? 2\\d\\d(?: |$)").containsMatchIn(it) } == true
            }
        }) return@synchronized null
        exhaustedRequest = request
        CarPlayBonjourEvent.EndpointsExhausted(request)
    }

    private fun probeRetryDelayMillis(attempt: Int): Long =
        (200L * (1L shl (attempt - 1).coerceIn(0, 10))).coerceAtMost(PROBE_RETRY_DELAY_MILLIS)

    private fun probeOnce(address: InetAddress, port: Int, attempt: Int, requestId: Int,
        service: InterfaceService? = null): String {
        val socket = createSocket()
        var stage = CarPlayBonjourEvent.ProbeProgress.Stage.CONNECTING
        try {
            synchronized(lifecycleLock) {
                if (!isCurrentProbe(requestId) || service != null && !isCurrentInterfaceService(service)) {
                    throw IOException("CarPlay discovery request was cancelled")
                }
                activeSockets.add(socket)
                service?.socket = socket
            }
            emit(CarPlayBonjourEvent.ProbeProgress(CarPlayBonjourEvent.ProbeProgress.Stage.CONNECTING, attempt, address is Inet6Address))
            sourceAddressFor(address)?.let { socket.bind(InetSocketAddress(it, 0)) }
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS)
            stage = CarPlayBonjourEvent.ProbeProgress.Stage.TCP_CONNECTED
            emit(CarPlayBonjourEvent.ProbeProgress(CarPlayBonjourEvent.ProbeProgress.Stage.TCP_CONNECTED, attempt, address is Inet6Address))
            socket.soTimeout = READ_TIMEOUT_MILLIS
            val host = address.hostAddress
                ?: throw IOException("AirPlay control service has no host address")
            val request = CarPlayBonjourProtocol.connectProbeRequest(
                host = host,
                port = port,
                sourceVersion = config.sourceVersion,
                deviceId = config.deviceId,
            )
            val output = socket.getOutputStream()
            output.write(request.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            stage = CarPlayBonjourEvent.ProbeProgress.Stage.REQUEST_SENT
            emit(CarPlayBonjourEvent.ProbeProgress(CarPlayBonjourEvent.ProbeProgress.Stage.REQUEST_SENT, attempt, address is Inet6Address))
            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII),
            )
            return reader.readLine()
                ?: throw IOException("AirPlay control probe returned no status line")
        } catch (error: IOException) {
            if (isCurrentProbe(requestId) && (service == null || isCurrentInterfaceService(service))) {
                emit(CarPlayBonjourEvent.ProbeFailed(stage, attempt, error))
            }
            throw error
        } finally {
            synchronized(lifecycleLock) {
                activeSockets.remove(socket)
                if (service != null && service.socket === socket) service.socket = null
            }
            runCatching { socket.close() }
        }
    }

    private fun sourceAddressFor(target: InetAddress): InetAddress? =
        advertisedAddresses.firstOrNull { (it is Inet4Address) == (target is Inet4Address) }

    private fun isCurrentProbe(requestId: Int): Boolean =
        !closed && controlProbesEnabled && probeRequestId.get() == requestId

    private fun isCurrentInterfaceService(service: InterfaceService): Boolean = synchronized(lifecycleLock) {
        isCurrentProbe(service.requestId) && currentInterfaceServices[service.serviceKey] === service
    }

    private fun emit(event: CarPlayBonjourEvent) {
        if (closed) return
        when (event) {
            is CarPlayBonjourEvent.Discovery -> when (event.stage) {
                CarPlayBonjourEvent.Discovery.Stage.ADDED -> addedCount.incrementAndGet()
                CarPlayBonjourEvent.Discovery.Stage.NO_MATCHING_ADDRESS -> addressMismatchCount.incrementAndGet()
                else -> Unit
            }
            is CarPlayBonjourEvent.Resolved -> resolvedCount.incrementAndGet()
            is CarPlayBonjourEvent.EndpointsExhausted -> lastProbe.set("endpoints_exhausted")
            is CarPlayBonjourEvent.ProbeProgress -> {
                if (event.stage == CarPlayBonjourEvent.ProbeProgress.Stage.CONNECTING) probeCount.incrementAndGet()
                lastProbe.set(event.stage.name)
            }
            is CarPlayBonjourEvent.ProbeFailed -> lastProbe.set("failed_after_${event.stage}_${event.error.javaClass.simpleName}")
            is CarPlayBonjourEvent.Probed -> {
                val status = event.statusLine?.let { Regex("^HTTP/\\d(?:\\.\\d)? (\\d{3})(?: |$)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                if (status != null && status in 200..299) successfulProbeCount.incrementAndGet()
                if (event.error == null) lastProbe.set("status_${status ?: "unknown"}")
            }
        }
        try {
            onEvent(event)
        } catch (error: RuntimeException) {
            Log.w(TAG, "CarPlay Bonjour event callback failed", error)
        }
    }

    private fun decodeTxtValue(value: ByteArray): String =
        String(value, StandardCharsets.UTF_8).trimEnd('\u0000')

    private fun joinWorker(worker: Thread) {
        try {
            worker.join(JOIN_TIMEOUT_MILLIS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while closing Bonjour worker", interrupted)
        }
        if (worker.isAlive) throw IOException("Bonjour worker is still running")
    }

    private companion object {
        val diagnosticIds = AtomicInteger()
        const val TAG = "xcertplay-bonjour"
        const val WORKER_NAME = "carplay-bonjour"
        const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp"
        const val CARPLAY_CONTROL_SERVICE_TYPE = "_carplay-ctrl._tcp"
        const val WORKER_POLL_MILLIS = 500L
        const val RESOLVE_ATTEMPTS = 3
        const val RESOLVE_TIMEOUT_MILLIS = 10_000L
        const val RESOLVE_RETRY_DELAY_MILLIS = 250L
        const val MAX_PROBE_ATTEMPTS = 7
        const val RECOVERY_BROWSE_ROUNDS = 3
        const val PROBE_RETRY_DELAY_MILLIS = 1_500L
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        const val READ_TIMEOUT_MILLIS = 3_000
        const val JOIN_TIMEOUT_MILLIS = 2_000L
        const val FAILURE_NONE = -1
    }
}
