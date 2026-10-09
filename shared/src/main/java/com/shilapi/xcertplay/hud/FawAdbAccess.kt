package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.transport.VehicleGear

/** What the settings page shows about the ADB link that DiPlay's optional BYD features need. */
object FawAdbAccess {
    enum class State { READY, NOT_APPROVED, ADB_OFF, PAIRING_ONLY }

    class Status(
        val state: State,
        val dashboardMode: FawClusterNaviMode? = null,
        val dashboardShowsMap: Boolean = true,
        val batteryPercent: Double? = null,
        val rangeKm: Int? = null,
        val speedKmh: Double? = null,
        val gear: VehicleGear? = null,
    )

    /** No vendor-specific ADB handshake or vehicle-service access in the HS5 build. */
    fun check(context: Context, mayAsk: Boolean): Status = Status(State.ADB_OFF)
    fun checkState(context: Context, mayAsk: Boolean): State = State.ADB_OFF
    internal fun state(access: LocalAdb.Access): State = when (access) {
        LocalAdb.Access.READY -> State.READY
        LocalAdb.Access.NOT_APPROVED -> State.NOT_APPROVED
        LocalAdb.Access.UNREACHABLE -> State.ADB_OFF
        LocalAdb.Access.UNSUPPORTED -> State.PAIRING_ONLY
    }
    internal fun readStatus(context: Context, shell: (String) -> String?): Status = Status(State.ADB_OFF)
}
