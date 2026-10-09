package com.shilapi.xcertplay.media

import android.view.MotionEvent
import com.shilapi.xcertplay.airplay.AirPlayContact

/** Each host keeps Android pointer identities stable for the whole HID gesture. */
class CarPlayTouchMapper {
    private val pointers = arrayOfNulls<Int>(2)

    fun clear() { pointers.fill(null) }

    fun contacts(event: MotionEvent, viewWidth: Int, viewHeight: Int): List<AirPlayContact> =
        contacts(event, CarPlayVideoLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()))

    fun contacts(event: MotionEvent, content: CarPlayVideoLayout): List<AirPlayContact> {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) clear()
        val allUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        val lifted = if (action == MotionEvent.ACTION_POINTER_UP) event.getPointerId(event.actionIndex) else null
        for (index in 0 until event.pointerCount) {
            val id = event.getPointerId(index)
            if (id !in pointers) {
                val slot = pointers.indexOfFirst { it == null }
                if (slot >= 0) pointers[slot] = id
            }
        }
        val result = pointers.mapIndexedNotNull { slot, id ->
            val index = id?.let(event::findPointerIndex) ?: -1
            if (index < 0) return@mapIndexedNotNull null
            AirPlayContact(
                id = slot,
                x = ((event.getX(index) - content.left).toDouble() / content.width.coerceAtLeast(1f)).coerceIn(0.0, 1.0),
                y = ((event.getY(index) - content.top).toDouble() / content.height.coerceAtLeast(1f)).coerceIn(0.0, 1.0),
                down = !allUp && id != lifted,
            )
        }
        if (allUp) clear() else pointers.indices.forEach { if (pointers[it] == lifted) pointers[it] = null }
        return result
    }
}
