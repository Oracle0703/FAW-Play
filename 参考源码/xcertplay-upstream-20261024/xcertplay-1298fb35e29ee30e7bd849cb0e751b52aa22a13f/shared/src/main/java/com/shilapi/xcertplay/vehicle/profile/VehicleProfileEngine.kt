package com.shilapi.xcertplay.vehicle.profile

import com.shilapi.xcertplay.transport.*
import com.shilapi.xcertplay.vehicle.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One immutable profile per session. All SDK reads occur off the iAP2/UI threads. */
class VehicleProfileEngine(
    private val backend: VehiclePropertyBackend,
    val profile: VehicleProfile,
    private val config: VehicleDataConfig,
    private val now: () -> Long,
    private val deviceSdk: Int,
    private val servicePresent: (String) -> Boolean,
    private val diagnostic: Boolean = false,
) : VehicleStatusProvider, VehicleSpeedSource {
    data class SignalState(val signal: ProfileSignal, val raw: RawPropertySample? = null,
        val normalized: NormalizedField = NormalizedField(reason = "WAITING"))
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"vehicle-profile").apply { isDaemon=true } }
    private val ready = Object()
    @Volatile private var closed = false
    @Volatile private var started = false
    @Volatile private var speedRequested = false
    @Volatile var subscriptionActive = false; private set
    @Volatile var catalog = PropertyCatalog(emptyList(),"Waiting for service"); private set
    @Volatile var states: Map<String,SignalState> = profile.signals.associate { it.id to SignalState(it) }; private set
    @Volatile private var preflightUntil = 0L
    private var discoveryAt = Long.MIN_VALUE
    private var diagnosticUntil = 0L
    private val denied = mutableSetOf<String>()
    private val readAt = mutableMapOf<String,Long>()
    val availabilityReason get() = backend.availabilityReason
    private fun enabled(target: CarPlayTarget) = target in profile.carplay && config.isEnabled(target.feature)
    private fun wanted(target: CarPlayTarget) = when(target.feature) {
        VehicleDataFeature.WHEEL_SPEED -> speedRequested
        else -> subscriptionActive || now()<preflightUntil
    }
    fun start(): Boolean {
        if(closed) return false
        if(started) return true
        // Nothing this session could report: skip the car service binding and the 5 Hz worker.
        if(!diagnostic && profile.carplay.keys.none { enabled(it) }) return false
        if(!backend.connect()) return false
        started=true; preflightUntil=now()+2000; diagnosticUntil=now()+600000
        worker.scheduleWithFixedDelay({ if(!closed) tick() },0,200,TimeUnit.MILLISECONDS)
        return true
    }
    private fun descriptor(key: PropertyKey) = catalog.properties.firstOrNull { it.propertyId==key.propertyId && key.areaId in it.areas }
    fun matchReason(): String? {
        if(profile.androidSdk != null && profile.androidSdk != deviceSdk) return "DEVICE_SDK_MISMATCH"
        if(profile.servicePackage != null && !servicePresent(profile.servicePackage)) return "SERVICE_PACKAGE_MISMATCH"
        if(profile.requires.any { key -> descriptor(key)?.let { d -> d.type != PropertyType.of(key.propertyId)?.name || !d.readable } != false }) return "REQUIRED_CONFIG_MISSING_OR_INACCESSIBLE"
        return null
    }
    /** Exposed for deterministic read-plan/lifecycle tests; production calls it only from worker. */
    internal fun tick() {
        val time=now()
        if(!backend.isConnected) {
            states=profile.signals.associate { it.id to SignalState(it,normalized=NormalizedField(reason=backend.availabilityReason ?: "DISCONNECTED")) }
            synchronized(ready) { ready.notifyAll() }; return
        }
        if(discoveryAt==Long.MIN_VALUE || catalog.properties.isEmpty() && time-discoveryAt>=1000) {
            catalog=backend.discoverProperties(); discoveryAt=time
        }
        val desired=if(diagnostic && time<diagnosticUntil) profile.signals.map { it.id }.toSet()
            else profile.carplay.filterKeys { enabled(it) && wanted(it) }.values.toSet()
        val next=states.toMutableMap()
        // Prioritize mapped signals; four getters per tick caps reads at twenty/second.
        val ordered=profile.signals.sortedWith(compareBy<ProfileSignal> { readAt[it.id] ?: Long.MIN_VALUE }.thenBy { if(it.target!=null) 0 else 1 })
        var reads=0
        ordered.filter { it.id in desired }.forEach { signal ->
            val d=descriptor(signal.key)
            val rateInterval=d?.maximumRateHz?.takeIf { it.isFinite() && it>0 }?.let { kotlin.math.ceil(1000.0/it).toLong() } ?: 0L
            if(signal.id in denied || reads>=4 || readAt[signal.id]?.let { time-it<maxOf(signal.pollMillis,rateInterval) }==true) return@forEach
            readAt[signal.id]=time
            val reason=when { d==null -> "CONFIG_MISSING_OR_INACCESSIBLE"; !d.readable -> "CONFIG_NOT_READABLE"; d.type!=signal.type.name -> "CONFIG_TYPE_MISMATCH"; else -> null }
            if(reason!=null) { next[signal.id]=SignalState(signal,normalized=NormalizedField(reason=reason)); return@forEach }
            reads++
            next[signal.id]=try {
                val sample=backend.readRaw(signal.key)
                when {
                    sample==null -> SignalState(signal,normalized=NormalizedField(reason="EMPTY_GETTER"))
                    sample.key!=signal.key -> SignalState(signal,normalized=NormalizedField(reason="RESPONSE_ID_AREA_MISMATCH"))
                    sample.value==null -> SignalState(signal,sample,NormalizedField(reason=if(sample.status==0) "EMPTY_GETTER" else "PROPERTY_STATUS_${sample.status}"))
                    sample.status!=0 && !signal.ignoreStatus -> SignalState(signal,sample,NormalizedField(reason="PROPERTY_STATUS_${sample.status}"))
                    else -> {
                        val normalized=VehicleNormalizer.normalize(signal,sample.value)
                        // An accepted non-zero status stays visible instead of being silently dropped.
                        val labelled=if(sample.status!=0 && normalized.reason==null)
                            normalized.copy(reason="ACCEPTED_STATUS_${sample.status}") else normalized
                        SignalState(signal,sample,labelled)
                    }
                }
            } catch(e: Exception) { if(e is SecurityException) denied.add(signal.id); SignalState(signal,normalized=NormalizedField(reason=if(e is SecurityException) "PERMISSION_DENIED" else "READ_ERROR:${e.javaClass.simpleName}")) }
        }
        states=next.toMap(); synchronized(ready) { ready.notifyAll() }
    }
    fun current(id: String?): NormalizedField {
        if(closed || !backend.isConnected) return NormalizedField(reason="DISCONNECTED")
        val state=states[id] ?: return NormalizedField(reason="NO_FIELD")
        val raw=state.raw ?: return state.normalized
        val limit=state.signal.maxAgeMillis
        // Continuous HAL properties and targets that need the source's own clock check event time too.
        val needsEventTime=descriptor(state.signal.key)?.changeMode==2 || state.signal.target?.requiresEventTime==true
        val eventAge=raw.eventMillis?.let { now()-it }
        if(now()-raw.observedMillis !in 0..limit || needsEventTime && (eventAge==null || eventAge !in 0..limit)) return NormalizedField(reason="STALE_OR_MISSING_EVENT_TIME")
        return state.normalized
    }
    fun value(target: CarPlayTarget): NormalizedField {
        matchReason()?.let { return NormalizedField(reason=it) }
        if(!enabled(target)) return NormalizedField(reason="DISABLED")
        return current(profile.carplay[target])
    }
    override fun snapshot(): VehicleStatusSnapshot? {
        if(closed || diagnostic) return null
        return CarPlayTarget.snapshot(::value).takeIf { Iap2VehicleStatus.capabilities(it).isNotEmpty() }
    }
    override fun latestSpeed(): VehicleSpeedReading? {
        if(closed || diagnostic) return null
        val speed=value(CarPlayTarget.WHEEL_SPEED).number ?: return null
        val gear=value(CarPlayTarget.GEAR).token ?: return null
        val signal=profile.signal(CarPlayTarget.WHEEL_SPEED) ?: return null
        val magnitude=VehicleNormalizer.wheelMagnitude(speed,signal.direction ?: return null,gear) ?: return null
        val timestamp=states[signal.id]?.raw?.eventMillis ?: return null
        return VehicleSpeedReading(timestamp,magnitude,when(gear) { "P" -> VehicleGear.PARK; "R" -> VehicleGear.REVERSE; "N" -> VehicleGear.NEUTRAL; "D" -> VehicleGear.DRIVE; else -> return null })
    }
    private fun <T> await(timeout:Long, getter:()->T?):T? {
        val deadline=System.nanoTime()+timeout.coerceIn(0,5000)*1_000_000
        synchronized(ready) { while(!closed && getter()==null) {
            val remaining=(deadline-System.nanoTime())/1_000_000
            if(remaining<=0) break
            ready.wait(remaining)
        } }
        return getter()
    }
    override fun awaitSnapshot(timeoutMillis:Long):VehicleStatusSnapshot? {
        preflightUntil=now()+timeoutMillis.coerceIn(0,5000)
        return await(timeoutMillis,::snapshot).also { preflightUntil=0 }
    }
    override fun awaitSpeed(timeoutMillis:Long):VehicleSpeedReading? {
        val old=speedRequested; requestSpeed(true)
        return await(timeoutMillis,::latestSpeed).also { speedRequested=old }
    }
    override fun requestSpeed(enabled:Boolean) { speedRequested=enabled && !closed }
    override fun onSubscriptionChanged(active:Boolean) { subscriptionActive=active; if(!active) preflightUntil=0 }
    override fun close() { closed=true; subscriptionActive=false; speedRequested=false; worker.shutdownNow(); backend.close(); synchronized(ready){ready.notifyAll()} }
}
