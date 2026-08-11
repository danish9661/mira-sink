package com.mira.sink.rtsp

import android.util.Log
import com.mira.sink.Phase
import com.mira.sink.SinkState
import com.mira.sink.StatusBus
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Locale

class RtspConnection(
    socket: Socket,
    private val host: SessionHost
) : Runnable {

    companion object {
        private const val TAG = "MiraRTSP"
        private const val TIMEOUT_MS = 15_000
        const val PUBLIC = "Public: OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, TEARDOWN"
        const val USER_AGENT = "User-Agent: Mira/1.0"
    }

    private val remote: Socket = socket
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    @Volatile
    private var running = true

    @Volatile
    var started = false
        private set

    private val remoteIp: String = socket.inetAddress?.hostAddress ?: "?"

    private var capsExchanged = false
    private var portsSent = false
    private var setupSent = false
    private var playSent = false
    private var streamStarted = false
    private var sourceSetupReceived = false

    override fun run() {
        try {
            remote.soTimeout = TIMEOUT_MS
            StatusBus.log("RTSP: source connected from $remoteIp")
            StatusBus.update(
                SinkState(Phase.RTSP_NEGOTIATING, "Negotiating with $remoteIp…", "")
            )
            mainLoop()
        } catch (t: Throwable) {
            Log.i(TAG, "RTSP session ended: ${t.message}")
        } finally {
            closeInternal()
        }
    }

    private fun mainLoop() {
        while (running) {
            val msg = try {
                RtspMessage.read(input)
            } catch (e: SocketTimeoutException) {
                Log.i(TAG, "RTSP read timeout, closing session")
                break
            } catch (e: IOException) {
                if (running) Log.i(TAG, "RTSP socket error: ${e.message}")
                break
            }
            if (msg == null) break
            if (msg.method == "RTSP/1.0") {
                onResponse(msg)
            } else {
                dispatch(msg)
            }
        }
    }

    private fun onResponse(msg: RtspMessage) {
        val code = msg.uri.toIntOrNull() ?: 0
        Log.i(TAG, "Received RTSP response $code (cseq=${msg.cseq})")
        when {
            playSent && code in 200..299 -> onStreamReady()
            setupSent && code in 200..299 -> sendPlayRequest()
            portsSent && code in 200..299 -> { /* M4 acknowledged */ }
        }
    }

    private fun dispatch(msg: RtspMessage) {
        when (msg.method) {
            "OPTIONS" -> handleOptions(msg)
            "GET_PARAMETER" -> handleGetParameter(msg)
            "SET_PARAMETER" -> handleSetParameter(msg)
            "SETUP" -> handleSetup(msg)
            "PLAY" -> handlePlay(msg)
            "TEARDOWN" -> handleTeardown(msg)
            "PAUSE" -> sendResponse(msg.cseq, 200, "OK", "", started)
            else -> {
                Log.w(TAG, "Unhandled RTSP method ${msg.method}")
                sendResponse(msg.cseq, 404, "Not Found", "", started)
            }
        }
    }

    private fun handleOptions(msg: RtspMessage) {
        Log.i(TAG, "M1: OPTIONS from source")
        started = true
        sendResponse(msg.cseq, 200, "OK", "", started)
        sendOptionsRequest()
        host.onRtspOptionsReceived()
    }

    private fun sendOptionsRequest() {
        sendRequest("OPTIONS", "rtsp://$remoteIp/wfd1.0", "", extra = listOf())
    }

    private fun handleGetParameter(msg: RtspMessage) {
        val body = buildCapabilityResponse(msg)
        if (body.isNotEmpty()) {
            Log.i(TAG, "M3: GET_PARAMETER capabilities exchange")
            StatusBus.log("RTSP: capability exchange")
            capsExchanged = true
        } else {
            Log.i(TAG, "GET_PARAMETER keepalive")
            if (msg.hasParameter("wfd_idr_request")) {
                sendResponse(msg.cseq, 200, "OK", "wfd_idr_request: 1\r\n", started)
                return
            }
        }
        sendResponse(msg.cseq, 200, "OK", body, started)
        maybeSendPorts()
    }

    private fun buildCapabilityResponse(msg: RtspMessage): String {
        val sb = StringBuilder()
        val port = host.bindRtp()
        for (line in msg.bodyLines) {
            val name = line.trim()
            when {
                name.startsWith("wfd_video_formats") ->
                    sb.append("wfd_video_formats: ${Capabilities.DEFAULT_VIDEO_FORMATS}\r\n")
                name.startsWith("wfd_audio_codecs") ->
                    sb.append("wfd_audio_codecs: ${Capabilities.WFD_AUDIO_CODECS}\r\n")
                name.startsWith("wfd_uibc_capability") ->
                    sb.append("wfd_uibc_capability: ${Capabilities.uibcCapability(Capabilities.UIBC_PORT)}\r\n")
                name.startsWith("wfd_3d_video_formats") ->
                    sb.append("wfd_3d_video_formats: ${Capabilities.WFD_3D_VIDEO_FORMATS}\r\n")
                name.startsWith("wfd_content_protection") ->
                    sb.append("wfd_content_protection: ${Capabilities.WFD_CONTENT_PROTECTION}\r\n")
                name.startsWith("wfd_connector_type") ->
                    sb.append("wfd_connector_type: ${Capabilities.WFD_CONNECTOR_TYPE}\r\n")
                name.startsWith("wfd_coupled_sink_info") ->
                    sb.append("wfd_coupled_sink_info: ${Capabilities.WFD_COUPLED_SINK_INFO}\r\n")
                name.startsWith("wfd_standby_resume_capability") ->
                    sb.append("wfd_standby_resume_capability: ${Capabilities.WFD_STANDBY_RESUME}\r\n")
                name.startsWith("wfd_idr_request_capability") ->
                    sb.append("wfd_idr_request_capability: ${Capabilities.WFD_IDR_REQUEST}\r\n")
                name.startsWith("wfd_rtsp_ports") ->
                    sb.append("wfd_rtsp_ports: ${Capabilities.RTSP_PORT}\r\n")
                name.startsWith("wfd_client_rtp_ports") ->
                    sb.append("wfd_client_rtp_ports: $port ${port + 1}\r\n")
                name.startsWith("wfd_connector_type") ||
                    name.startsWith("wfd_audio_codecs") -> { /* covered above */ }
            }
        }
        return sb.toString()
    }

    private fun maybeSendPorts() {
        if (capsExchanged && !portsSent) {
            portsSent = true
            val port = host.bindRtp()
            Log.i(TAG, "M4: sending wfd_client_rtp_ports=$port ${port + 1}")
            sendRequest(
                "SET_PARAMETER",
                "rtsp://$remoteIp/wfd1.0",
                "wfd_client_rtp_ports=$port ${port + 1}\r\n" +
                    "wfd_uibc_capability=${Capabilities.uibcCapability(Capabilities.UIBC_PORT)}\r\n"
            )
        }
    }

    private fun handleSetParameter(msg: RtspMessage) {
        for (line in msg.bodyLines) {
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            when {
                name == "wfd_trigger_method" -> {
                    Log.i(TAG, "M5: SET_PARAMETER trigger=$value")
                    if (value == "play" && !sourceSetupReceived) beginSetup()
                    if (value == "close") running = false
                }
                name == "wfd_uibc_capability" -> {
                    val uibc = !value.startsWith("none")
                    Log.i(TAG, "SET_PARAMETER uibc_enabled=$uibc ($value)")
                    host.setUibcEnabled(uibc)
                }
                name == "wfd_video_formats" -> host.onVideoFormatsChosen(value)
                name == "wfd_audio_codecs" -> Log.i(TAG, "SET_PARAMETER audio: $value")
                name == "wfd_presentation_url" -> Log.i(TAG, "SET_PARAMETER presentation_url: $value")
            }
        }
        sendResponse(msg.cseq, 200, "OK", "", started)
    }

    private fun beginSetup() {
        if (setupSent || sourceSetupReceived) return
        setupSent = true
        val port = host.bindRtp()
        Log.i(TAG, "M6: sending SETUP client_port=$port-${port + 1}")
        sendRequest(
            "SETUP",
            "rtsp://$remoteIp/wfd1.0/streamid=0",
            "",
            extra = listOf("Transport: RTP/AVP/UDP;unicast;client_port=$port-${port + 1};mode=play")
        )
    }

    private fun sendPlayRequest() {
        if (playSent) return
        playSent = true
        Log.i(TAG, "M7: sending PLAY")
        sendRequest(
            "PLAY",
            "rtsp://$remoteIp/wfd1.0/streamid=0",
            "",
            extra = listOf("Range: npt=0.000-")
        )
    }

    private fun handleSetup(msg: RtspMessage) {
        Log.i(TAG, "SETUP received from source: ${msg.headers["Transport"]}")
        sendResponse(
            msg.cseq, 200, "OK", "", started,
            extra = listOf("Transport: ${msg.headers["Transport"] ?: "RTP/AVP/UDP;unicast"}")
        )
        sourceSetupReceived = true
    }

    private fun handlePlay(msg: RtspMessage) {
        Log.i(TAG, "PLAY received from source")
        sendResponse(msg.cseq, 200, "OK", "", started)
        onStreamReady()
    }

    private fun onStreamReady() {
        if (!streamStarted) {
            streamStarted = true
            Log.i(TAG, "Streaming session established with $remoteIp")
            host.startStreaming()
        }
    }

    private fun handleTeardown(msg: RtspMessage) {
        Log.i(TAG, "TEARDOWN from source")
        sendResponse(msg.cseq, 200, "OK", "", started)
        running = false
    }

    private fun sendRequest(
        method: String,
        uri: String,
        body: String,
        cseq: Int = 100,
        extra: List<String> = emptyList()
    ) {
        val sb = StringBuilder()
        sb.append("$method $uri RTSP/1.0\r\n")
        sb.append("CSeq: $cseq\r\n")
        sb.append("$USER_AGENT\r\n")
        for (h in extra) sb.append("$h\r\n")
        sb.append("Content-Type: text/parameters\r\n")
        sb.append("Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n")
        sb.append("\r\n")
        sb.append(body)
        output.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
        output.flush()
        Log.i(TAG, "Sent -> $method $uri")
    }

    private fun sendResponse(
        cseq: String,
        code: Int,
        reason: String,
        body: String,
        includeSession: Boolean = false,
        extra: List<String> = emptyList()
    ) {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 $code $reason\r\n")
        sb.append("CSeq: $cseq\r\n")
        if (includeSession) sb.append("Session: ${Capabilities.SESSION_ID}\r\n")
        for (h in extra) sb.append("$h\r\n")
        if (body.isNotEmpty()) sb.append("Content-Type: text/parameters\r\n")
        sb.append("Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n")
        sb.append("\r\n")
        sb.append(body)
        output.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    fun requestIdr() {
        try {
            sendRequest("GET_PARAMETER", "rtsp://$remoteIp/wfd1.0", "wfd_idr_request\r\n")
        } catch (t: Throwable) {
            Log.w(TAG, "IDR request failed", t)
        }
    }

    fun close() {
        running = false
        try {
            remote.close()
        } catch (t: Throwable) {
        }
    }

    private fun closeInternal() {
        close()
        host.onSessionClosed()
    }
}

interface SessionHost {
    fun bindRtp(): Int
    fun startStreaming()
    fun setUibcEnabled(enabled: Boolean)
    fun onVideoFormatsChosen(value: String)
    fun onRtspOptionsReceived()
    fun onSessionClosed()
}