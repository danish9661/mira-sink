package com.mira.sink.rtsp

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

interface RtspListener {
    fun onRtspConnectionOpened(conn: RtspConnection)
    fun onRtspClosed()
    fun bindRtpPort(): Int
    fun startStreaming()
    fun setUibcEnabled(enabled: Boolean)
    fun onVideoFormatsChosen(value: String)
    fun onRtspOptionsReceived()
}

class RtspServer(private val listener: RtspListener) {

    companion object {
        private const val TAG = "MiraRTSP"
    }

    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    private val thread = Thread({ acceptLoop() }, "rtsp-server")

    fun start() {
        if (running) return
        running = true
        thread.start()
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(Capabilities.RTSP_PORT))
            server = ss
            Log.i(TAG, "RTSP server listening on port ${Capabilities.RTSP_PORT}")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to bind RTSP port", t)
            running = false
            return
        }
        while (running) {
            try {
                val socket = server!!.accept()
                socket.tcpNoDelay = true
                listener.onRtspConnectionOpened(RtspConnection(socket, object : SessionHost {
                    override fun bindRtp(): Int = listener.bindRtpPort()
                    override fun startStreaming() = listener.startStreaming()
                    override fun setUibcEnabled(enabled: Boolean) = listener.setUibcEnabled(enabled)
                    override fun onVideoFormatsChosen(value: String) = listener.onVideoFormatsChosen(value)
                    override fun onRtspOptionsReceived() = listener.onRtspOptionsReceived()
                    override fun onSessionClosed() = listener.onRtspClosed()
                }))
            } catch (t: IOException) {
                if (!running) break
                Log.w(TAG, "RTSP accept error", t)
            }
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (t: Throwable) {
        }
        thread.join(2000)
        server = null
    }
}