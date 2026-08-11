package com.mira.sink.uibc

import android.util.Log
import com.mira.sink.StatusBus
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

class UibcServer(private val port: Int) {

    companion object {
        private const val TAG = "MiraUIBC"

        const val EV_UP = 0x01
        const val EV_DOWN = 0x02
        const val EV_MOVE = 0x03
        const val EV_CANCEL = 0x04
        const val EV_HOVER = 0x05

        const val CMD_STOP = 0x00
        const val CMD_START = 0x01
        const val CMD_INPUT_READY = 0x06

        const val TYPE_INPUT_EVENT = 0x00
        const val TYPE_UIBC_COMMAND = 0x03

        private const val MAX_SCALE = 0xFFFFFFFFL
    }

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var output: OutputStream? = null

    @Volatile
    private var running = false

    private var thread: Thread? = null
    private val lock = Any()

    @Volatile
    var connected = false
        private set

    fun start() {
        synchronized(lock) {
            if (running) return
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                running = true
                thread = Thread({ acceptLoop() }, "uibc-server")
                thread!!.start()
                Log.i(TAG, "UIBC server listening on $port")
            } catch (t: Throwable) {
                Log.e(TAG, "UIBC bind failed", t)
            }
        }
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val s = serverSocket!!.accept()
                s.tcpNoDelay = true
                synchronized(lock) {
                    clientSocket?.close()
                    clientSocket = s
                    output = s.getOutputStream()
                    connected = true
                }
                StatusBus.log("UIBC: source connected (${s.inetAddress?.hostAddress})")
                sendUiibcCommand(CMD_INPUT_READY)
                readLoop(s)
            } catch (e: SocketException) {
                if (running) Log.i(TAG, "UIBC accept closed")
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "UIBC accept error", t)
            }
        }
    }

    private fun readLoop(s: Socket) {
        try {
            val input = s.getInputStream()
            s.soTimeout = 5000
            val buf = ByteArray(1024)
            while (running && s == clientSocket) {
                val n = try {
                    input.read(buf)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                if (n > 0) {
                    if (buf[0].toInt() == 0) connected = false
                } else break
            }
        } catch (t: Throwable) {
            Log.d(TAG, "UIBC read ended")
        } finally {
            synchronized(lock) {
                if (s == clientSocket) {
                    connected = false
                    clientSocket = null
                    output = null
                    Log.i(TAG, "UIBC client disconnected")
                }
            }
            try {
                s.close()
            } catch (t: Throwable) {
            }
        }
    }

    fun sendUiibcCommand(command: Int) {
        val payload = ByteArray(1)
        payload[0] = command.toByte()
        sendPacket(TYPE_UIBC_COMMAND, payload)
    }

    fun sendTouchEvent(
        eventType: Int,
        pointerId: Int,
        xNorm: Float,
        yNorm: Float
    ) {
        if (!connected) return
        val payload = ByteArray(11)
        payload[0] = eventType.toByte()
        payload[1] = 0x00
        payload[2] = pointerId.toByte()
        putUnsignedInt(payload, 3, xNorm)
        putUnsignedInt(payload, 7, yNorm)
        sendPacket(TYPE_INPUT_EVENT, payload)
    }

    fun sendMouseEvent(
        eventType: Int,
        xNorm: Float,
        yNorm: Float,
        button: Int
    ) {
        if (!connected) return
        val payload = ByteArray(13)
        payload[0] = eventType.toByte()
        payload[1] = 0x10
        payload[2] = 0x00
        payload[3] = button.toByte()
        putUnsignedInt(payload, 4, xNorm)
        putUnsignedInt(payload, 8, yNorm)
        sendPacket(TYPE_INPUT_EVENT, payload)
    }

    private fun putUnsignedInt(payload: ByteArray, offset: Int, value: Float) {
        val v = (value.coerceIn(0f, 1f) * MAX_SCALE).toLong()
        payload[offset] = (v ushr 24).toByte()
        payload[offset + 1] = (v ushr 16).toByte()
        payload[offset + 2] = (v ushr 8).toByte()
        payload[offset + 3] = v.toByte()
    }

    private fun sendPacket(type: Int, payload: ByteArray) {
        val out = output ?: return
        val packet = ByteArray(payload.size + 4)
        packet[0] = 0x01
        packet[1] = type.toByte()
        val totalLen = packet.size
        packet[2] = (totalLen ushr 8).toByte()
        packet[3] = totalLen.toByte()
        System.arraycopy(payload, 0, packet, 4, payload.size)
        try {
            synchronized(lock) {
                out.write(packet)
                out.flush()
            }
        } catch (t: IOException) {
            Log.w(TAG, "UIBC send failed", t)
            connected = false
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            try {
                serverSocket?.close()
            } catch (t: Throwable) {
            }
            serverSocket = null
            try {
                clientSocket?.close()
            } catch (t: Throwable) {
            }
            clientSocket = null
            output = null
            connected = false
        }
        thread?.join(1500)
        thread = null
        Log.i(TAG, "UIBC server stopped")
    }
}