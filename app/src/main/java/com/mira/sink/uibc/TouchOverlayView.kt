package com.mira.sink.uibc

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View

@SuppressLint("ViewConstructor")
class TouchOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val TAG = "MiraTouch"
    }

    @Volatile
    var uibc: UibcServer? = null

    private var activePointerId = -1
    private var lastX = 0f
    private var lastY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val server = uibc ?: return false
        if (!server.connected) return false

        parent?.requestDisallowInterceptTouchEvent(true)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                server.sendTouchEvent(
                    UibcServer.EV_DOWN,
                    pointerIdFor(event),
                    event.x / width,
                    event.y / height
                )
                Log.d(TAG, "DOWN (${event.x / width}, ${event.y / height})")
            }
            MotionEvent.ACTION_MOVE -> {
                if (activePointerId < 0) return true
                val id = pointerIdFor(event)
                server.sendTouchEvent(
                    UibcServer.EV_MOVE,
                    id,
                    event.x / width,
                    event.y / height
                )
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activePointerId < 0) return true
                val id = pointerIdFor(event)
                val type = if (event.actionMasked == MotionEvent.ACTION_UP) {
                    UibcServer.EV_UP
                } else {
                    UibcServer.EV_CANCEL
                }
                server.sendTouchEvent(type, id, event.x / width, event.y / height)
                activePointerId = -1
            }
        }
        return true
    }

    private fun pointerIdFor(event: MotionEvent): Int {
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            event.getPointerId(0)
        } else {
            event.getPointerId(0)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setOnClickListener { /* consume taps */ }
    }
}