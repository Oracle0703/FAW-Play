package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.Locale

internal enum class SelectedPhoneState { UNKNOWN, CONNECTED, DISCONNECTED, UNAVAILABLE }

/** Observes the selected phone's system Bluetooth link; never starts pairing or a connection. */
internal class SelectedPhoneMonitor internal constructor(
    context: Context,
    private val onState: (SelectedPhoneState) -> Unit,
    private val diagnostic: (String) -> Unit,
    private val selectedAddress: () -> String?,
    private val adapterProvider: () -> BluetoothAdapter?,
    private val initialConnection: (BluetoothDevice) -> Boolean? = ::readDeviceConnection,
) : AutoCloseable {
    constructor(
        context: Context,
        onState: (SelectedPhoneState) -> Unit,
        diagnostic: (String) -> Unit = {},
    ) : this(context, onState, diagnostic, { DiPlayPreferences.phoneAddress(context) }, {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    })

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var registered = false
    @Volatile private var closed = false
    private var generation = 0
    private var target: String? = null
    private var adapter: BluetoothAdapter? = null
    private var pollAllowed = false
    private var waitingPoll: Runnable? = null
    private var disconnectedSince: Long? = null
    private var aclConnected: Boolean? = null
    private var initialDeviceConnected: Boolean? = null
    private val profileStates = mutableMapOf<Int, ProfileState>()
    private val profileRevisions = mutableMapOf<Int, Int>()
    private val profileRequests = mutableMapOf<Int, Int>()
    private val proxies = mutableMapOf<Int, BluetoothProfile>()
    @Volatile var state = SelectedPhoneState.UNKNOWN
        private set

    private enum class ProfileState { PENDING, CONNECTED, DISCONNECTED, UNAVAILABLE }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onMain {
            if (closed || !started || intent.action !in ACTIONS) return@onMain
            if (readTarget() != target) reload()
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> reload()
                    BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                        invalidateProfiles()
                        aclConnected = null
                        initialDeviceConnected = null
                        publish(if (target == null) SelectedPhoneState.DISCONNECTED else SelectedPhoneState.UNAVAILABLE)
                    }
                }
                return@onMain
            }
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                ?: return@onMain
            if (!matches(device)) return@onMain
            // The first boot read may precede the adapter service. A real device event must
            // not remain blocked by that earlier null handle or a missed STATE_ON broadcast.
            val current = try { adapterProvider()?.takeIf { it.isEnabled } } catch (_: SecurityException) {
                pollAllowed = false
                null
            }
            if (current == null) {
                resetEvidence(target, null)
                publish(SelectedPhoneState.UNAVAILABLE)
                return@onMain
            }
            if (adapter !== current) resetEvidence(target, current)
            pollAllowed = registered
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    disconnectedSince = null
                    aclConnected = true
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    disconnectedSince = null
                    aclConnected = false
                    initialDeviceConnected = null
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                        BluetoothDevice.BOND_NONE -> {
                            invalidateProfiles()
                            aclConnected = false
                            initialDeviceConnected = false
                        }
                        BluetoothDevice.BOND_BONDED -> { reload(); return@onMain }
                        else -> return@onMain
                    }
                }
                else -> {
                    val profile = PROFILE_ACTIONS[intent.action] ?: return@onMain
                    val profileState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (profileState != BluetoothProfile.STATE_CONNECTED &&
                        profileState != BluetoothProfile.STATE_DISCONNECTED) return@onMain
                    profileRevisions[profile] = (profileRevisions[profile] ?: 0) + 1
                    when (profileState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            disconnectedSince = null
                            aclConnected = null
                            initialDeviceConnected = null
                            profileStates[profile] = ProfileState.CONNECTED
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            if (aclConnected != false) aclConnected = null
                            initialDeviceConnected = null
                            profileStates[profile] = ProfileState.DISCONNECTED
                        }
                        else -> return@onMain
                    }
                }
            }
            publishCurrent()
        }
    }

    fun start() = onMain {
        if (closed || started) return@onMain
        started = true
        reload()
    }

    private fun registerReceiver() {
        if (registered) return
        try {
            ContextCompat.registerReceiver(context, receiver,
                IntentFilter().apply { ACTIONS.forEach(::addAction) }, null, main,
                ContextCompat.RECEIVER_EXPORTED)
            registered = true
        } catch (_: SecurityException) {
            diagnostic("phone-presence receiver unavailable")
        }
    }

    /** Re-read the preference and fresh profile snapshots after configuration or service changes. */
    fun refresh() = onMain {
        if (closed) return@onMain
        if (!started) start() else reload()
    }

    private fun readTarget(): String? = selectedAddress()?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)

    private fun reload() {
        registerReceiver()
        val nextTarget = readTarget()
        if (nextTarget == null) {
            pollAllowed = false
            resetEvidence(nextTarget, null)
            publish(SelectedPhoneState.DISCONNECTED)
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            pollAllowed = false
            resetEvidence(nextTarget, null)
            publish(SelectedPhoneState.UNAVAILABLE)
            return
        }
        pollAllowed = registered
        val current = try { adapterProvider()?.takeIf { it.isEnabled } } catch (_: SecurityException) {
            pollAllowed = false
            null
        }
        if (current == null || !registered) {
            resetEvidence(nextTarget, current)
            publish(SelectedPhoneState.UNAVAILABLE)
            return
        }
        val sameSource = nextTarget.equals(target, ignoreCase = true) && adapter === current
        if (!sameSource) resetEvidence(nextTarget, current)
        val device = try { current.bondedDevices.orEmpty().firstOrNull(::matches) }
            catch (_: SecurityException) {
                pollAllowed = false
                resetEvidence(nextTarget, null)
                publish(SelectedPhoneState.UNAVAILABLE)
                return
            }
        if (device == null) {
            resetEvidence(nextTarget, current)
            publish(SelectedPhoneState.DISCONNECTED)
            return
        }
        // A routine refresh must not manufacture a departure/arrival while proxies bind.
        // Keep established evidence until a link event or confirmed device read revokes it.
        val connected = initialConnection(device)
        when (connected) {
            true -> {
                disconnectedSince = null
                initialDeviceConnected = true
                if (aclConnected == false) aclConnected = null
            }
            false -> if (state == SelectedPhoneState.CONNECTED) {
                val now = SystemClock.elapsedRealtime()
                val since = disconnectedSince
                if (since == null) disconnectedSince = now
                else if (now - since >= WAITING_POLL_MILLIS) {
                    // Two fresh negative device reads revoke stale positive observations;
                    // a single transient read or empty phone-role profile does not.
                    invalidateProfiles()
                    aclConnected = false
                    initialDeviceConnected = false
                    disconnectedSince = null
                }
            } else initialDeviceConnected = false
            null -> disconnectedSince = null
        }
        // These are new reads, rather than the initial asynchronous snapshot. OEM stacks may
        // update their proxy without delivering a connection broadcast to this process.
        if (state != SelectedPhoneState.CONNECTED && connected != false) proxies.forEach { (id, proxy) ->
            val phonePresent = try { proxy.connectedDevices.orEmpty().any(::matches) }
                catch (_: SecurityException) { false }
            if (phonePresent) {
                profileStates[id] = ProfileState.CONNECTED
                aclConnected = null
            }
        }
        publishCurrent()
        PROFILES.forEach { profile ->
            if (profileStates[profile] == null || profileStates[profile] == ProfileState.UNAVAILABLE)
                requestProfile(current, profile)
        }
        publishCurrent()
    }

    private fun resetEvidence(nextTarget: String?, current: BluetoothAdapter?) {
        invalidateProfiles()
        target = nextTarget
        adapter = current
        disconnectedSince = null
        aclConnected = null
        initialDeviceConnected = null
    }

    private fun requestProfile(current: BluetoothAdapter, profile: Int) {
        val attempt = generation
        val revision = profileRevisions[profile] ?: 0
        val request = (profileRequests[profile] ?: 0) + 1
        profileRequests[profile] = request
        profileStates[profile] = ProfileState.PENDING
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) = onMain {
                if (closed || generation != attempt || profileId != profile ||
                    profileRequests[profile] != request || readTarget() != target) {
                    release(current, profileId, proxy)
                    return@onMain
                }
                proxies.put(profileId, proxy)?.takeIf { it !== proxy }?.let { release(current, profileId, it) }
                if ((profileRevisions[profile] ?: 0) == revision) {
                    profileStates[profileId] = try {
                        if (proxy.connectedDevices.orEmpty().any(::matches)) ProfileState.CONNECTED
                        else ProfileState.DISCONNECTED
                    } catch (_: SecurityException) { ProfileState.UNAVAILABLE }
                }
                publishCurrent()
            }

            override fun onServiceDisconnected(profileId: Int) = onMain {
                if (closed || generation != attempt || profileId != profile ||
                    profileRequests[profile] != request || readTarget() != target) return@onMain
                if ((profileRevisions[profile] ?: 0) != revision ||
                    profileStates[profileId] == ProfileState.CONNECTED) return@onMain
                profileStates[profileId] = ProfileState.UNAVAILABLE
                publishCurrent()
            }
        }
        val requested = try { current.getProfileProxy(context, listener, profile) }
            catch (_: SecurityException) { false }
        if (!requested && profileStates[profile] == ProfileState.PENDING)
            profileStates[profile] = ProfileState.UNAVAILABLE
    }

    private fun matches(device: BluetoothDevice): Boolean = try {
        target != null && device.address.equals(target, ignoreCase = true)
    } catch (_: SecurityException) { false }

    private fun publishCurrent() {
        val next = when {
            target == null -> SelectedPhoneState.DISCONNECTED
            aclConnected == true -> SelectedPhoneState.CONNECTED
            aclConnected == false -> SelectedPhoneState.DISCONNECTED
            profileStates.values.any { it == ProfileState.CONNECTED } -> SelectedPhoneState.CONNECTED
            initialDeviceConnected == true -> SelectedPhoneState.CONNECTED
            initialDeviceConnected == false -> SelectedPhoneState.DISCONNECTED
            profileStates.values.any { it == ProfileState.PENDING } -> SelectedPhoneState.UNKNOWN
            else -> SelectedPhoneState.UNKNOWN
        }
        publish(next)
    }

    private fun publish(next: SelectedPhoneState) {
        if (closed) return
        val changed = state != next
        state = next
        updateWaitingPoll()
        if (!changed) return
        diagnostic("phone-presence state=$next")
        onState(next)
    }

    private fun updateWaitingPoll() {
        if (!started || closed || target == null || !registered || !pollAllowed) {
            cancelWaitingPoll()
            return
        }
        if (waitingPoll != null) return
        val attempt = generation
        val selected = target
        // A snapshot read compensates for missing OEM broadcasts; it never scans, pairs,
        // enables the radio, or starts an Activity. Null/off adapters remain retryable too.
        val check = object : Runnable {
            override fun run() {
                if (waitingPoll !== this) return
                waitingPoll = null
                if (closed || !started || generation != attempt || target != selected) return
                reload()
            }
        }
        waitingPoll = check
        main.postDelayed(check, WAITING_POLL_MILLIS)
    }

    private fun cancelWaitingPoll() {
        waitingPoll?.let(main::removeCallbacks)
        waitingPoll = null
    }

    private fun invalidateProfiles() {
        cancelWaitingPoll()
        generation++
        val previous = adapter
        proxies.forEach { (id, proxy) -> if (previous != null) release(previous, id, proxy) }
        proxies.clear()
        profileStates.clear()
        profileRevisions.clear()
        profileRequests.clear()
    }

    private fun release(adapter: BluetoothAdapter, id: Int, proxy: BluetoothProfile) {
        try { adapter.closeProfileProxy(id, proxy) } catch (_: RuntimeException) {
            diagnostic("phone-presence profile release unavailable")
        }
    }

    override fun close() {
        closed = true
        onMain {
            invalidateProfiles()
            if (registered) {
                try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
                registered = false
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post(block)
    }

    private companion object {
        const val WAITING_POLL_MILLIS = 2_000L
        // The existing wireless selector uses this compatibility read on API 28 car systems.
        // Public phone-role profiles alone cannot prove an automotive client/sink disconnected.
        fun readDeviceConnection(device: BluetoothDevice): Boolean? = try {
            BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as? Boolean
        } catch (_: ReflectiveOperationException) { null } catch (_: RuntimeException) { null }

        val PROFILES = listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)
        // Automotive stacks use the client/sink roles; their broadcasts are still system-only.
        val PROFILE_ACTIONS = mapOf(
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED to BluetoothProfile.HEADSET,
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED to BluetoothProfile.A2DP,
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED" to 16,
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED" to 11,
        )
        val ACTIONS = PROFILE_ACTIONS.keys + setOf(BluetoothAdapter.ACTION_STATE_CHANGED,
            BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED,
            BluetoothDevice.ACTION_BOND_STATE_CHANGED)
    }
}
