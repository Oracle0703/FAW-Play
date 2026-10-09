package com.shilapi.xcertplay

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button

internal fun descendants(view: View): Sequence<View> = sequence {
    yield(view)
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}

internal fun settingsButton(activity: Activity, text: String): Button =
    descendants(activity.window.decorView).filterIsInstance<Button>()
        .single { it.text.toString() == text }

internal fun selectSettingsTab(activity: Activity, text: String) {
    check(settingsButton(activity, text).performClick())
}
