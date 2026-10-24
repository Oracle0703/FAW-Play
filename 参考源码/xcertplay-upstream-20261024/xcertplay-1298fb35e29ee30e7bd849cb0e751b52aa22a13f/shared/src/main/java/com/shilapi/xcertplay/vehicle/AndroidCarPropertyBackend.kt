package com.shilapi.xcertplay.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.shilapi.xcertplay.vehicle.profile.*

/** No android.car class is linked on phones, and no setProperty method is used. */
class AndroidCarPropertyBackend(context: Context) : ReadOnlyCarPropertyBackend, VehiclePropertyBackend {
    private val context = context.applicationContext
    private val stateLock = Any()
    @Volatile private var failure: String? = "car property service not started"
    @Volatile private var car: Any? = null
    @Volatile private var manager: Any? = null
    @Volatile private var closed = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) = synchronized(stateLock) {
            if (!closed) {
                manager = runCatching { car?.javaClass?.getMethod("getCarManager", String::class.java)
                    ?.invoke(car, "property") }.getOrNull()
                failure = if (manager == null) "car property manager unavailable" else null
            }
        }
        override fun onServiceDisconnected(name: ComponentName) = synchronized(stateLock) { manager = null; failure = "car service disconnected" }
    }
    override fun connect(): Boolean = synchronized(stateLock) {
        if (closed) return false
        if (car != null) return true
        return try {
            val type = Class.forName("android.car.Car")
            car = type.getMethod("createCar", Context::class.java, ServiceConnection::class.java)
                .invoke(null, context, connection)
            failure = "waiting for car service"
            type.getMethod("connect").invoke(car)
            true // asynchronous binding; reads return null until connected
        } catch (error: Exception) {
            failure = if (error is ClassNotFoundException) "android.car shared library unavailable" else "car service connection failed"
            val oldCar = car
            car = null
            manager = null
            runCatching { oldCar?.javaClass?.getMethod("disconnect")?.invoke(oldCar) }
            Log.i("VehicleData", "Android Car property service unavailable", error)
            false
        }
    }
    override val isConnected: Boolean get() = !closed && manager != null
    override val availabilityReason: String? get() = failure
    override fun read(propertyId: Int): CarPropertyValue? {
        val raw = readRaw(PropertyKey(propertyId)) ?: return null
        if (raw.status != 0) return null
        return (raw.value as? Number)?.let { CarPropertyValue(it,raw.eventMillis) }
    }
    private fun invoke(target: Any, name: String, vararg arguments: Any): Any? {
        val types = arguments.map { if (it is Int) Int::class.javaPrimitiveType!! else it.javaClass }.toTypedArray()
        return try { target.javaClass.getMethod(name,*types).invoke(target,*arguments) }
        catch (error: java.lang.reflect.InvocationTargetException) { throw (error.targetException as? Exception ?: error) }
    }
    override fun readRaw(key: PropertyKey): RawPropertySample? {
        val target = manager ?: return null
        val result = invoke(target,"getProperty",key.propertyId,key.areaId) ?: return null
        require(invoke(result,"getPropertyId") == key.propertyId && invoke(result,"getAreaId") == key.areaId) { "Property response ID/area mismatch" }
        val status = invoke(result,"getStatus") as? Int ?: return null
        // The value is captured even when the status is non-zero: some vendor HALs report a
        // meaningless status for good readings, and only the profile may decide to trust it.
        val value = runCatching { invoke(result,"getValue") }.getOrNull()
        // Keep supported primitive/array values for diagnosis; they never become arbitrary code.
        val allowed = value == null || value is Number || value is Boolean || value is String ||
            value is IntArray || value is FloatArray || value is LongArray || value is ByteArray ||
            value is Array<*> && value.all { it is Number || it is Boolean || it is String }
        if (!allowed) throw UnsupportedOperationException("Unsupported property value type")
        val event = (invoke(result,"getTimestamp") as? Long)?.takeIf { it > 0 }?.div(1_000_000)
        return RawPropertySample(key,value,status,event,android.os.SystemClock.elapsedRealtime())
    }
    override fun discoverProperties(): PropertyCatalog {
        val target = manager ?: return PropertyCatalog(emptyList(),availabilityReason ?: "Waiting for car service")
        return try {
            val names = linkedMapOf<Int,MutableList<String>>()
            runCatching { Class.forName("android.car.VehiclePropertyIds").fields.forEach { field ->
                if (field.type == Int::class.javaPrimitiveType && java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                    names.getOrPut(field.getInt(null)) { arrayListOf() }.add(field.name)
                }
            } }
            val configs = invoke(target,"getPropertyList") as? List<*> ?: return PropertyCatalog(emptyList(),"Empty property list")
            val items = configs.take(1000).mapNotNull { config ->
                runCatching {
                if (config == null) return@runCatching null
                val id = invoke(config,"getPropertyId") as? Int ?: return@runCatching null
                val type = invoke(config,"getPropertyType") as? Class<*>
                // CarPropertyConfig reports boxed classes; vectors are Integer[], Long[] and Float[].
                val typeName = when (type) {
                    Int::class.javaObjectType, Int::class.javaPrimitiveType -> PropertyType.INT32.name
                    Long::class.javaObjectType, Long::class.javaPrimitiveType -> PropertyType.INT64.name
                    Float::class.javaObjectType, Float::class.javaPrimitiveType -> PropertyType.FLOAT32.name
                    Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType -> PropertyType.BOOLEAN.name
                    Array<Int>::class.java, IntArray::class.java -> PropertyType.INT32_VEC.name
                    Array<Long>::class.java, LongArray::class.java -> PropertyType.INT64_VEC.name
                    Array<Float>::class.java, FloatArray::class.java -> PropertyType.FLOAT32_VEC.name
                    else -> type?.name ?: "UNKNOWN"
                }
                val areas = (invoke(config,"getAreaIds") as? IntArray)?.toList().orEmpty()
                val global = runCatching { invoke(config,"isGlobalProperty") == true }.getOrDefault(false)
                PropertyDescriptor(id,names[id]?.toList().orEmpty(),typeName,
                    if (areas.isEmpty() && global) listOf(0) else areas,
                    invoke(config,"getAccess") as? Int ?: 0,invoke(config,"getChangeMode") as? Int ?: -1,
                    runCatching { invoke(config,"getMinSampleRate") as? Float }.getOrNull(),
                    runCatching { invoke(config,"getMaxSampleRate") as? Float }.getOrNull())
                }.onFailure { Log.w("VehicleData", "Cannot inspect property config", it) }.getOrNull()
            }
            PropertyCatalog(items)
        } catch (error: Exception) { PropertyCatalog(emptyList(),"${error.javaClass.simpleName}: ${error.message}") }
    }
    override fun close() = synchronized(stateLock) {
        closed = true
        failure = "car data source closed"
        manager = null
        val oldCar = car
        car = null
        runCatching { oldCar?.javaClass?.getMethod("disconnect")?.invoke(oldCar) }
        Unit
    }
}
