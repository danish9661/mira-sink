package com.mira.sink

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

enum class Phase {
    IDLE,
    P2P_SETUP,
    GROUP_READY,
    WAITING_CAST,
    RTSP_NEGOTIATING,
    STREAMING,
    ERROR
}

data class SinkState(
    val phase: Phase = Phase.IDLE,
    val message: String = "",
    val detail: String = "",
    val p2pSsid: String = "",
    val codec: String = "",
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val uibcActive: Boolean = false,
    val streamFps: Int = 0
)

interface StatusListener {
    fun onStateChanged(state: SinkState)
    fun onLog(line: String)
}

object StatusBus {
    private val listeners = CopyOnWriteArrayList<StatusListener>()
    private val main = Handler(Looper.getMainLooper())
    private val logTail = ArrayDeque<String>()

    @Volatile
    var state = SinkState()
        private set

    fun addListener(listener: StatusListener) {
        listeners.add(listener)
        listener.onStateChanged(state)
    }

    fun removeListener(listener: StatusListener) {
        listeners.remove(listener)
    }

    fun update(newState: SinkState) {
        state = newState
        main.post {
            for (l in listeners) l.onStateChanged(state)
        }
        log("STATE: ${state.phase} ${state.message} ${state.detail}")
    }

    fun log(line: String) {
        synchronized(logTail) {
            logTail.addLast(line)
            while (logTail.size > 200) logTail.removeFirst()
        }
        main.post {
            for (l in listeners) l.onLog(line)
        }
    }
}