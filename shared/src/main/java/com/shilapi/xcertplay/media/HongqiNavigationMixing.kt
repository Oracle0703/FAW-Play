package com.shilapi.xcertplay.media

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Temporary OEM DSP policy belongs to real CarPlay sessions, including background playback. */
internal object HongqiNavigationMixing {
    private const val KEY = "mixing_down_media_volume"
    private val owners = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private val worker by lazy { Handler(HandlerThread("autunn-navigation-mixing").apply { start() }.looper) }
    private val scheduled = AtomicBoolean()
    private var app: Context? = null
    private var report: (String) -> Unit = {}
    private var revision = 0L
    private var car: Any? = null // worker-thread only
    private var operationSequence = 0L
    private var currentOperation = 0L
    private var observer: ContentObserver? = null
    private var lease: NavigationMixingOwnership? = null

    @Synchronized fun recover(context: Context, diagnostic: (String) -> Unit) {
        if (Build.VERSION.SDK_INT != 28 || Build.MODEL != "HONG QI") return
        app = context.applicationContext
        report = diagnostic
        revision++
        schedule()
    }

    @Synchronized fun acquire(context: Context, owner: Any, diagnostic: (String) -> Unit) {
        if (Build.VERSION.SDK_INT != 28 || Build.MODEL != "HONG QI") return
        app = context.applicationContext
        report = diagnostic
        if (owners.add(owner)) { revision++; schedule() }
    }

    @Synchronized fun release(owner: Any) {
        if (owners.remove(owner)) { revision++; schedule() }
    }

    private fun schedule() {
        if (scheduled.compareAndSet(false, true)) worker.post(::reconcile)
    }

    private fun reconcile() {
        val context: Context
        val version: Long
        val active: Boolean
        synchronized(this) {
            context = app ?: run { scheduled.set(false); return }
            version = revision
            active = owners.isNotEmpty()
        }
        val operation = ++operationSequence
        currentOperation = operation
        val prefs = context.getSharedPreferences("faw_navigation_mixing", 0)
        if (!active && !prefs.getBoolean("restore_pending", false)) { finish(version, operation); return }
        fun disconnect() {
            val current = car
            car = null
            current?.let { runCatching { it.javaClass.getMethod("disconnect").invoke(it) } }
        }
        val timeout = Runnable { disconnect(); reportSafe("Audio: navigation mixing service timeout"); finish(version, operation) }
        try {
            val type = Class.forName("android.car.Car")
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    worker.post {
                        if (operation != currentOperation) return@post
                        val current = car ?: return@post
                        worker.removeCallbacks(timeout)
                        try {
                            val audio = current.javaClass.getMethod("getCarManager", String::class.java).invoke(current, "audio")
                            val ownership = lease ?: NavigationMixingOwnership(
                                read = { runCatching { Settings.System.getInt(context.contentResolver, KEY, 1) != 0 }.getOrNull() },
                                apply = { value ->
                                    audio.javaClass.getMethod("turnDownMediaVolumeWhenDuckedByNavi", Boolean::class.javaPrimitiveType)
                                        .invoke(audio, value)
                                    reportSafe("Audio: navigation mixing OEM applied=$value")
                                },
                                pending = { prefs.getBoolean("restore_pending", false) },
                                record = { pending -> check(prefs.edit().putBoolean("restore_pending", pending).commit()) },
                            )
                            // The API object belongs to this connection; the policy retains only state.
                            lease = ownership.withApplier { value ->
                                audio.javaClass.getMethod("turnDownMediaVolumeWhenDuckedByNavi", Boolean::class.javaPrimitiveType)
                                    .invoke(audio, value)
                                reportSafe("Audio: navigation mixing OEM applied=$value")
                            }
                            if (observer == null) {
                                observer = object : ContentObserver(worker) {
                                    override fun onChange(selfChange: Boolean) { lease?.observedExternalChange() }
                                }.also { context.contentResolver.registerContentObserver(Settings.System.getUriFor(KEY), false, it) }
                            }
                            val wanted = synchronized(this@HongqiNavigationMixing) { owners.isNotEmpty() }
                            lease?.reconcile(wanted)
                        } catch (failure: Exception) {
                            reportSafe("Audio: navigation mixing unavailable ${failure.javaClass.simpleName}")
                        } finally { disconnect(); finish(version, operation) }
                    }
                }
                override fun onServiceDisconnected(name: ComponentName) { worker.post {
                    if (operation == currentOperation) { worker.removeCallbacks(timeout); disconnect(); finish(version, operation) }
                } }
            }
            car = type.getMethod("createCar", Context::class.java, ServiceConnection::class.java).invoke(null, context, connection)
            type.getMethod("connect").invoke(car)
            worker.postDelayed(timeout, 5_000)
        } catch (failure: Exception) {
            disconnect()
            reportSafe("Audio: navigation mixing unavailable ${failure.javaClass.simpleName}")
            finish(version, operation)
        }
    }

    private fun finish(version: Long, operation: Long) {
        if (currentOperation != operation) return
        currentOperation = 0
        lease?.withApplier { throw IllegalStateException("OEM service is disconnected") }
        scheduled.set(false)
        synchronized(this) {
            if (revision != version) schedule()
            else if (owners.isEmpty()) report = {}
        }
    }

    private fun reportSafe(line: String) { runCatching { report(line) } }
}
