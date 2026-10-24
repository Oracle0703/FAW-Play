package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** One launch/foreground-confirmation policy for the service and the visible software page. */
internal class AutomaticEntryDispatcher(
    private val context: Context,
    private val requester: Any? = null,
    private val eligible: () -> Boolean = { true },
) {
    private val main = Handler(Looper.getMainLooper())
    private val check = Runnable { dispatch() }
    private var followUp: Runnable? = null
    private var followUpState: CarPlayBackgroundSession.BackgroundEntryFollowUp? = null

    fun schedule() {
        main.removeCallbacks(check)
        main.post(check)
    }

    fun cancel() {
        main.removeCallbacks(check)
        followUp?.let(main::removeCallbacks)
        followUp = null
        followUpState = null
    }

    fun dispatch() {
        if (!eligible()) { cancel(); return }
        observeFollowUp()
        val launch = CarPlayBackgroundSession.claimBackgroundEntry(requester) ?: return
        val open = Intent(context, CarPlayHostActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION,
        ).putExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, launch.id)
        try {
            context.startActivity(open)
            CarPlayBackgroundSession.recordUiDiagnostic(
                "background auto-entry launch requested id=${launch.id} attempt=${launch.attempt}",
            )
        } catch (error: RuntimeException) {
            CarPlayBackgroundSession.recordUiDiagnostic(
                "background auto-entry launch rejected=${error.javaClass.simpleName}; notification entry available",
            )
            CarPlayBackgroundSession.deferBackgroundEntryRetry(launch.id, error.javaClass.simpleName, launch.attempt)
        }
        observeFollowUp()
    }

    private fun observeFollowUp() {
        val state = CarPlayBackgroundSession.backgroundEntryFollowUp()
        if (state == followUpState) return
        followUp?.let(main::removeCallbacks)
        followUp = null
        followUpState = state
        if (state == null) return
        val task = object : Runnable {
            override fun run() {
                if (followUp !== this) return
                followUp = null
                followUpState = null
                if (!eligible()) return
                if (state.retryWait) CarPlayBackgroundSession.prepareBackgroundEntryRetry(state.id, state.attempt)
                else CarPlayBackgroundSession.deferBackgroundEntryRetry(state.id, "foreground-timeout", state.attempt)
                dispatch()
            }
        }
        followUp = task
        main.postDelayed(task, (state.dueAtMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }
}
