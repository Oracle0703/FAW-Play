package com.shilapi.xcertplay.hud
import android.content.Context
/** Compatibility for older UI callers. This HS5 build never holds another vendor's stock map. */
object FawOemClusterNavi {
    fun applicable(context: Context): Boolean = false
    fun holdForLaunch(context: Context, lease: String, current: () -> Boolean): Boolean = false
    fun release(context: Context, lease: String? = null) {}
    fun restoreIfNeeded(context: Context) {}
}
