package com.mira.testsource

import android.app.Activity
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {

    companion object {
        private const val TAG = "MiraTest"
        private const val RTSP_PORT = 7236
        private const val UIBC_PORT = 7237
        private const val HOST = "127.0.0.1"
    }

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SourceTest.appFilesDir = filesDir.absolutePath
        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(24, 24, 24, 24)
        }
        setContentView(LinearLayout(this).apply {
            gravity = Gravity.TOP or Gravity.START
            addView(statusView)
        })

        val extras = intent.extras
        val codec = extras?.getInt("codec", 0) ?: 0
        val w = extras?.getInt("w", 1280) ?: 1280
        val h = extras?.getInt("h", 720) ?: 720
        val fps = extras?.getInt("fps", 30) ?: 30
        val durS = extras?.getInt("dur", 10) ?: 10
        val uibc = extras?.getBoolean("uibc", true) ?: true

        Thread {
            SourceTest(codec, w, h, fps, durS, uibc) { line ->
                Log.i(TAG, line)
                runOnUiThread { statusView.text = statusView.text.toString() + "\n" + line }
            }.run()
        }.start()
    }

    private class Message(
        val method: String,
        val uri: String,
        val headers: Map<String, String>,
        val body: String
    ) {
        val cseq: String get() = headers["CSeq"] ?: "0"

        companion object {
            fun read(input: InputStream): Message? {
                val head = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b < 0) return null
                    head.append(b.toChar())
                    if (head.endsWith("\r\n\r\n")) break
                    if (head.length > 65536) throw RuntimeException("header too big")
                }
                val header = head.substring(0, head.length - 4)
                val lines = header.split("\r\n")
                val request = lines[0].split(" ")
                val headers = linkedMapOf<String, String>()
                for (i in 1 until lines.size) {
                    val idx = lines[i].indexOf(':')
                    if (idx > 0) {
                        headers[lines[i].substring(0, idx).trim()] =
                            lines[i].substring(idx + 1).trim()
                    }
                }
                val len = headers["Content-Length"]?.toIntOrNull() ?: 0
                val body = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = input.read(body, read, len - read)
                    if (n < 0) throw RuntimeException("socket closed in body")
                    read += n
                }
                return Message(
                    request.getOrElse(0) { "" },
                    request.getOrElse(1) { "" },
                    headers,
                    String(body, StandardCharsets.UTF_8)
                )
            }
        }
    }

    private class SourceTest(
        private val codecPref: Int,
        private val w: Int,
        private val h: Int,
        private val fps: Int,
        private val durS: Int,
        private val uibcEnable: Boolean,
        private val log: (String) -> Unit
    ) : Runnable {

        private var sinkRtpPort = 1550
        private var sourceRtpPort = 19000
        private var chosenMime = MediaFormat.MIMETYPE_VIDEO_AVC
        private var advertHasHevc = false
        private var sourceSocket: Socket? = null

        private val seq = AtomicInteger(0)
        private val ssrc = (Math.random() * Int.MAX_VALUE).toInt()

        companion object {
            lateinit var appFilesDir: String
        }

        override fun run() {
            val start = System.currentTimeMillis()
            try {
                log("[START] codecPref=$codecPref ${w}x$h@$fps dur=${durS}s uibc=$uibcEnable")
                var socket: Socket? = null
                var attempt = 0
                while (socket == null && attempt < 60) {
                    try {
                        socket = Socket(HOST, RTSP_PORT)
                    } catch (t: Throwable) {
                        attempt++
                        Thread.sleep(1000)
                    }
                }
                socket ?: throw RuntimeException("RTSP server not reachable after 60s")
                sourceSocket = socket
                socket.tcpNoDelay = true
                socket.soTimeout = 15000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                handshake(socket, input, output)

                val handshakeMs = System.currentTimeMillis() - start
                log("[HANDSHAKE] done in ${handshakeMs}ms sink_rtp=$sinkRtpPort mime=$chosenMime hevc_advertised=$advertHasHevc")

                val streaming = AtomicBoolean(true)
                val reader = Thread({ keepAliveLoop(input, output, streaming) }, "rtsp-keepalive")
                reader.start()

                val uibcResult = UibcResult()
                val uibcThread = if (uibcEnable) {
                    Thread({ uibcClientLoop(uibcResult, streaming) }, "uibc-client").apply { start() }
                } else null

                val streamResult = streamVideo(streaming)

                streaming.set(false)
                if (uibcThread != null) uibcThread.join(3000)
                try {
                    sendRequest(output, "TEARDOWN", "rtsp://$HOST/wfd1.0", "", 90)
                    Thread.sleep(100)
                } catch (t: Throwable) {
                }
                socket.close()

                log(
                    "SUMMARY codec=${if (chosenMime == MediaFormat.MIMETYPE_VIDEO_HEVC) "hevc" else "h264"} " +
                        "w=$w h=$h src_fps=${"%.1f".format(streamResult.fps)} frames=${streamResult.frames} " +
                        "rtp_pkts=${streamResult.pkts} rtp_bytes=${streamResult.bytes} " +
                        "handshake_ms=$handshakeMs total_ms=${System.currentTimeMillis() - start} " +
                        "uibc_pkts=${uibcResult.count} uibc_inputs=${uibcResult.inputs} uibc_first=${uibcResult.first}"
                )
            } catch (t: Throwable) {
                log("FAIL ${t.javaClass.simpleName}: ${t.message}")
                Log.e(TAG, "test failed", t)
            }
        }

        private val pending = ArrayDeque<Message>()

        private fun next(input: InputStream): Message {
            pending.removeFirstOrNull()?.let { return it }
            return Message.read(input) ?: throw RuntimeException("connection closed")
        }

        private fun waitFor(
            input: InputStream,
            output: OutputStream,
            label: String,
            pred: (Message) -> Boolean,
            alsoHandle: (Message) -> Boolean = { false }
        ): Message {
            while (true) {
                val m = next(input)
                if (pred(m)) return m
                if (alsoHandle(m)) continue
                pending.addLast(m)
            }
        }

        private fun is200(m: Message): Boolean = m.method == "RTSP/1.0" && m.uri == "200"

        private fun handshake(socket: Socket, input: InputStream, output: OutputStream) {
            sendRequest(output, "OPTIONS", "rtsp://$HOST/wfd1.0", "", 1)
            var m = waitFor(input, output, "M1", { is200(it) && it.cseq == "1" }) { msg ->
                if (msg.method == "OPTIONS") {
                    sendResponse(output, msg.cseq, 200, "")
                    log("[M2a] answered early OPTIONS")
                    true
                } else false
            }
            log("[M1] OPTIONS reply 200")

            socket.soTimeout = 1500
            try {
                val m2 = Message.read(input)
                if (m2 != null && m2.method == "OPTIONS") {
                    sendResponse(output, m2.cseq, 200, "")
                    log("[M2] answered sink OPTIONS")
                } else {
                    log("[M2] no sink OPTIONS within 1.5s (ok)")
                }
            } catch (t: Throwable) {
                log("[M2] no sink OPTIONS within 1.5s (timeout, ok)")
            }
            socket.soTimeout = 15000

            val gpBody = "wfd_video_formats\r\nwfd_audio_codecs\r\nwfd_uibc_capability\r\n" +
                "wfd_3d_video_formats\r\nwfd_content_protection\r\nwfd_connector_type\r\n" +
                "wfd_coupled_sink_info\r\nwfd_standby_resume_capability\r\n" +
                "wfd_idr_request_capability\r\nwfd_rtsp_ports\r\nwfd_client_rtp_ports\r\n"
            sendRequest(output, "GET_PARAMETER", "rtsp://$HOST/wfd1.0", gpBody, 3)
            val m3 = waitFor(input, output, "M3", { is200(it) && it.cseq == "3" }) { msg ->
                if (msg.method == "OPTIONS") {
                    sendResponse(output, msg.cseq, 200, "")
                    true
                } else false
            }
            val formats = m3.body.paramValue("wfd_video_formats") ?: ""
            advertHasHevc = formats.contains(", 0 1 0") || formats.contains(",0 1 0")
            m3.body.paramValue("wfd_client_rtp_ports")?.let {
                it.trim().split(Regex("\\s+"))[0].toIntOrNull()?.let { v -> sinkRtpPort = v }
            }
            log("[M3] got caps hevc_advertised=$advertHasHevc sink_rtp=$sinkRtpPort")

            val m4 = waitFor(input, output, "M4", { it.method == "SET_PARAMETER" }) { msg ->
                if (msg.method == "OPTIONS") {
                    sendResponse(output, msg.cseq, 200, "")
                    true
                } else false
            }
            m4.body.paramValue("wfd_client_rtp_ports")?.let {
                it.trim().split(Regex("\\s+"))[0].toIntOrNull()?.let { v -> sinkRtpPort = v }
            }
            sendResponse(output, m4.cseq, 200, "")
            log("[M4] sink SET_PARAMETER answered rtp=$sinkRtpPort")

            chosenMime = when {
                codecPref == 1 || (codecPref == 2 && advertHasHevc) ->
                    MediaFormat.MIMETYPE_VIDEO_HEVC
                else -> MediaFormat.MIMETYPE_VIDEO_AVC
            }
            val chosenEntry = if (chosenMime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                formats.substringAfter(",").trim()
            } else {
                formats.substringBefore(",").trim()
            }
            if (chosenEntry.isEmpty()) chosenMime = MediaFormat.MIMETYPE_VIDEO_AVC

            val m5Body = "wfd_video_formats=$chosenEntry\r\n" +
                "wfd_audio_codecs=LPCM 00000003 00\r\n" +
                "wfd_uibc_capability=port=$UIBC_PORT method=2 event=00000018\r\n" +
                "wfd_trigger_method=play\r\n"
            sendRequest(output, "SET_PARAMETER", "rtsp://$HOST/wfd1.0", m5Body, 5)
            var m6s: Message? = null
            waitFor(input, output, "M5", { is200(it) && it.cseq == "5" }) { msg ->
                if (msg.method == "SETUP") {
                    m6s = msg
                    true
                } else false
            }
            log("[M5] selection accepted")

            val m6 = m6s ?: waitFor(input, output, "M6", { it.method == "SETUP" })
            sendResponse(
                output, m6.cseq, 200, "",
                extra = "Transport: RTP/AVP/UDP;unicast;client_port=$sinkRtpPort-${sinkRtpPort + 1};" +
                    "server_port=$sourceRtpPort-${sourceRtpPort + 1};mode=play"
            )
            log("[M6] SETUP answered server_port=$sourceRtpPort")

            val m7 = waitFor(input, output, "M7", { it.method == "PLAY" })
            sendResponse(output, m7.cseq, 200, "")
            log("[M7] PLAY answered")
        }

        private fun keepAliveLoop(input: InputStream, output: OutputStream, streaming: AtomicBoolean) {
            try {
                while (streaming.get()) {
                    val msg = Message.read(input) ?: break
                    when (msg.method) {
                        "OPTIONS", "GET_PARAMETER", "SET_PARAMETER" ->
                            sendResponse(output, msg.cseq, 200, "")
                        "TEARDOWN" -> {
                            sendResponse(output, msg.cseq, 200, "")
                            streaming.set(false)
                        }
                    }
                }
            } catch (t: Throwable) {
            }
        }

        private class StreamResult {
            var frames = 0
            var pkts = 0L
            var bytes = 0L
            var firstPts = -1L
            var lastPts = -1L
            val fps: Double
                get() = if (lastPts > firstPts && frames > 1) {
                    (frames - 1) * 1_000_000.0 / (lastPts - firstPts)
                } else 0.0
        }

        private class UibcResult {
            var count = 0
            var inputs = 0
            var first = ""
        }

        private fun streamVideo(streaming: AtomicBoolean): StreamResult {
            val result = StreamResult()
            val udp = DatagramSocket()
            udp.soTimeout = 1000
            sourceRtpPort = udp.localPort
            val target = InetAddress.getByName(HOST)
            log("[UDP] sender port=$sourceRtpPort -> $HOST:$sinkRtpPort payload=96")

            val codec = setupEncoder() ?: return result
            try {
                val fw = (w + 15) and -16
                val fh = (h + 15) and -16
                val frameSize = fw * fh * 3 / 2

                var frameIdx = 0
                val startNs = System.nanoTime()
                val durationNs = durS * 1_000_000_000L
                codec.start()
                var encFile: java.io.FileOutputStream? = null
                try {
                    val ef = java.io.File(SourceTest.appFilesDir, "encoded.h265")
                    ef.parentFile?.mkdirs()
                    encFile = java.io.FileOutputStream(ef)
                    log("[ENC-DUMP] writing ${ef.absolutePath}")
                } catch (t: Throwable) {
                    log("[ENC-DUMP] open failed ${t.message}")
                }

                while (streaming.get() && System.nanoTime() - startNs < durationNs) {
                    val targetPtsUs = frameIdx * 1_000_000L / fps
                    val waitNs = startNs + (frameIdx + 1) * 1_000_000_000L / fps - System.nanoTime()
                    if (waitNs > 0) Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())

                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        buf.position(0)
                        fillYuvFrame(buf, fw, fh, w, h, frameIdx, frameSize)
                        codec.queueInputBuffer(idx, 0, frameSize, targetPtsUs, 0)
                        frameIdx++
                    }

                    drainEncoder(codec, udp, target, sinkRtpPort, result, encFile)

                    if (frameIdx % (fps * 2) == 0 && frameIdx > 0) {
                        log(
                            "[STREAM] ${frameIdx / fps}s frames=$frameIdx " +
                                "pkts=${result.pkts} bytes=${result.bytes} src_fps=${"%.1f".format(result.fps)}"
                        )
                    }
                }
                drainEncoder(codec, udp, target, sinkRtpPort, result, encFile)
                try {
                    encFile?.close()
                } catch (t: Throwable) {
                }
                log(
                    "[STREAM DONE] frames=${result.frames} pkts=${result.pkts} " +
                        "bytes=${result.bytes} src_fps=${"%.1f".format(result.fps)}"
                )
            } catch (t: Throwable) {
                log("STREAM FAIL ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                try {
                    codec.stop()
                } catch (t: Throwable) {
                }
                codec.release()
                udp.close()
            }
            return result
        }

        private fun setupEncoder(): MediaCodec? {
            val fw = (w + 15) and -16
            val fh = (h + 15) and -16
            if (!hasEncoder(chosenMime)) {
                log("[ENC] $chosenMime encoder NOT available; falling back")
                if (chosenMime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                    chosenMime = MediaFormat.MIMETYPE_VIDEO_AVC
                    if (!hasEncoder(chosenMime)) {
                        log("[ENC] no encoders at all")
                        return null
                    }
                }
            }
            val format = MediaFormat.createVideoFormat(chosenMime, fw, fh)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, fw * fh * 2)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            val codec = MediaCodec.createEncoderByType(chosenMime)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            log("[ENC] encoder ready ${chosenMime} ${fw}x$fh (from $w x $h)")
            return codec
        }

        private fun hasEncoder(mime: String): Boolean {
            if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC && Build.VERSION.SDK_INT < 21) return false
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (c in list.codecInfos) {
                if (c.isEncoder && c.supportedTypes.contains(mime)) return true
            }
            return false
        }

        private fun fillYuvFrame(buf: ByteBuffer, fw: Int, fh: Int, w: Int, h: Int, frame: Int, size: Int) {
            val slide = (frame * (w / 48)) % w
            for (y in 0 until fh) {
                for (x in 0 until fw) {
                    val v = if (x >= w || y >= h) 16 else (x + slide) % w * 255 / w
                    buf.put((v and 0xFF).toByte())
                }
            }
            val hw = fw / 2
            val hh = fh / 2
            for (y in 0 until hh) {
                for (x in 0 until hw) {
                    buf.put(((128 + ((x + frame / 2) % 4) * 16) and 0xFF).toByte())
                }
            }
            for (y in 0 until hh) {
                for (x in 0 until hw) {
                    buf.put(((128 + ((y + frame / 2) % 4) * 16) and 0xFF).toByte())
                }
            }
            buf.limit(size)
        }

        private fun drainEncoder(
            codec: MediaCodec,
            udp: DatagramSocket,
            target: InetAddress,
            dstPort: Int,
            result: StreamResult,
            encFile: java.io.FileOutputStream?
        ) {
            val info = MediaCodec.BufferInfo()
            var waited = false
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (waited) 0 else 10_000)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!waited) {
                        waited = true
                        continue
                    }
                    break
                }
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                if (idx < 0) continue
                val outBuf = codec.getOutputBuffer(idx) ?: run {
                    codec.releaseOutputBuffer(idx, false)
                    continue
                }
                if (info.size > 0) {
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    val data = ByteArray(info.size)
                    outBuf.get(data)
                    try {
                        encFile?.write(data)
                    } catch (t: Throwable) {
                    }
                    result.frames++
                    packetize(data, info.presentationTimeUs, udp, target, dstPort, result)
                }
                codec.releaseOutputBuffer(idx, false)
                waited = true
            }
        }

        private fun packetize(
            data: ByteArray,
            ptsUs: Long,
            udp: DatagramSocket,
            target: InetAddress,
            dstPort: Int,
            result: StreamResult
        ) {
            if (result.firstPts < 0) result.firstPts = ptsUs
            result.lastPts = ptsUs

            val nals = splitNals(data)
            if (nals.isEmpty()) return
            if (result.frames <= 1 && nals.size >= 3) {
                val raw = data.copyOfRange(0, minOf(64, data.size))
                    .joinToString("") { String.format("%02X", it) }
                Log.i(TAG, "[DUMP] frame=${result.frames} total=${data.size} raw=$raw")
                nals.take(minOf(3, nals.size)).forEachIndexed { idx, n ->
                    val from = n.first
                    val len = minOf(n.second, 16)
                    val hex = data.copyOfRange(from, from + len).joinToString("") { String.format("%02X", it) }
                    Log.i(TAG, "[DUMP] nal#$idx type=${(data[from].toInt() ushr 1) and 0x3F} len=${n.second} head=$hex")
                }
            }
            val ts = (ptsUs * 90 / 1000).toInt()
            val isHevc = chosenMime == MediaFormat.MIMETYPE_VIDEO_HEVC
            for (nal in nals) {
                val body = nal.first
                val len = nal.second
                val payload = data.copyOfRange(body, body + len)
                if (isHevc) {
                    sendHevcNal(payload, ts, udp, target, dstPort, result, isLast = nal == nals.last())
                } else {
                    sendH264Nal(payload, ts, udp, target, dstPort, result, isLast = nal == nals.last())
                }
            }
        }

        private fun splitNals(data: ByteArray): List<Pair<Int, Int>> {
            val out = mutableListOf<Pair<Int, Int>>()
            var i = 0
            var start: Int? = null
            while (i < data.size - 3) {
                val isSc = data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    ((data[i + 2] == 1.toByte()) ||
                        (data[i + 2] == 0.toByte() && i + 3 < data.size && data[i + 3] == 1.toByte()))
                if (isSc) {
                    if (start != null) {
                        out.add(Pair(start, i - start))
                    }
                    val four = data[i + 2] == 0.toByte()
                    start = i + if (four) 4 else 3
                    i += if (four) 4 else 3
                } else {
                    i++
                }
            }
            if (start != null) out.add(Pair(start, data.size - start))
            return out
        }

        private fun sendH264Nal(
            payload: ByteArray,
            ts: Int,
            udp: DatagramSocket,
            target: InetAddress,
            dstPort: Int,
            result: StreamResult,
            isLast: Boolean
        ) {
            val nalType = payload[0].toInt() and 0x1F
            val marker = isLast
            if (payload.size <= 1350) {
                sendRtp(payload, ts, marker, udp, target, dstPort, result)
                return
            }
            val fuIndicator = (0x60 or 28).toByte()
            var offset = 0
            while (offset < payload.size) {
                val chunk = minOf(1350 - 2, payload.size - offset)
                val firstFrag = offset == 0
                val lastFrag = offset + chunk >= payload.size
                val fu = ByteArray(chunk + 2)
                fu[0] = fuIndicator
                fu[1] = ((if (firstFrag) 0x80 else 0) or (if (lastFrag) 0x40 else 0) or nalType).toByte()
                System.arraycopy(payload, offset, fu, 2, chunk)
                sendRtp(fu, ts, isLast && lastFrag, udp, target, dstPort, result)
                offset += chunk
            }
        }

        private fun sendHevcNal(
            payload: ByteArray,
            ts: Int,
            udp: DatagramSocket,
            target: InetAddress,
            dstPort: Int,
            result: StreamResult,
            isLast: Boolean
        ) {
            if (payload.size <= 1350) {
                sendRtp(payload, ts, isLast, udp, target, dstPort, result)
                return
            }
            val nalType = (payload[0].toInt() ushr 1) and 0x3F
            val fu = ByteArray(3)
            fu[0] = ((49 shl 1) or (payload[0].toInt() and 0x01)).toByte()
            fu[1] = payload[1]
            var offset = 2
            while (offset < payload.size) {
                val chunk = minOf(1350 - 3, payload.size - offset)
                val firstFrag = offset == 2
                val lastFrag = offset + chunk >= payload.size
                val pkt = ByteArray(chunk + 3)
                pkt[0] = fu[0]
                pkt[1] = fu[1]
                pkt[2] = ((if (firstFrag) 0x80 else 0) or (if (lastFrag) 0x40 else 0) or nalType).toByte()
                System.arraycopy(payload, offset, pkt, 3, chunk)
                sendRtp(pkt, ts, isLast && lastFrag, udp, target, dstPort, result)
                offset += chunk
            }
        }

        private fun sendRtp(
            payload: ByteArray,
            ts: Int,
            marker: Boolean,
            udp: DatagramSocket,
            target: InetAddress,
            dstPort: Int,
            result: StreamResult
        ) {
            val pkt = ByteArray(12 + payload.size)
            pkt[0] = 0x80.toByte()
            pkt[1] = ((if (marker) 0x80 else 0) or 96).toByte()
            val s = seq.getAndIncrement()
            pkt[2] = (s ushr 8).toByte()
            pkt[3] = s.toByte()
            pkt[4] = (ts ushr 24).toByte()
            pkt[5] = (ts ushr 16).toByte()
            pkt[6] = (ts ushr 8).toByte()
            pkt[7] = ts.toByte()
            pkt[8] = (ssrc ushr 24).toByte()
            pkt[9] = (ssrc ushr 16).toByte()
            pkt[10] = (ssrc ushr 8).toByte()
            pkt[11] = ssrc.toByte()
            System.arraycopy(payload, 0, pkt, 12, payload.size)
            try {
                udp.send(DatagramPacket(pkt, pkt.size, target, dstPort))
                result.pkts++
                result.bytes += pkt.size
            } catch (t: Throwable) {
            }
        }

        private fun uibcClientLoop(result: UibcResult, streaming: AtomicBoolean) {
            try {
                val s = Socket(HOST, UIBC_PORT)
                s.soTimeout = 3000
                log("[UIBC] connected to $HOST:$UIBC_PORT")
                val input = s.getInputStream()
                val buf = ByteArray(4096)
                while (streaming.get()) {
                    val n = try {
                        input.read(buf)
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    } catch (t: Throwable) {
                        break
                    }
                    if (n <= 0) break
                    var off = 0
                    while (off + 4 <= n) {
                        val type = buf[off + 1].toInt() and 0xFF
                        val len = ((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)
                        if (off + len > n) break
                        val evType = buf[off + 4].toInt() and 0xFF
                        var desc = "unknown"
                        var extra = ""
                        if (type == 0x00 && len >= 12) {
                            result.inputs++
                            val x = readU32(buf, off + 7)
                            val y = readU32(buf, off + 11)
                            val name = when (evType) {
                                0x01 -> "UP"
                                0x02 -> "DOWN"
                                0x03 -> "MOVE"
                                0x04 -> "CANCEL"
                                else -> "EV$evType"
                            }
                            desc = name
                            extra = "x=${"%.3f".format(x / 4294967295.0)} y=${"%.3f".format(y / 4294967295.0)}"
                            if (result.inputs == 1) {
                                log("[UIBC] first input: $desc $extra")
                            }
                        }
                        result.count++
                        if (result.first.isEmpty()) {
                            result.first = "type=$type $desc $extra"
                            log("[UIBC] first: type=$type len=$len $desc $extra")
                        }
                        off += len
                    }
                }
                s.close()
            } catch (t: Throwable) {
                log("[UIBC] ended: ${t.message}")
            }
        }

        private fun readU32(b: ByteArray, off: Int): Long {
            return ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
                ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)
        }

        private fun readRequired(input: InputStream): Message {
            return Message.read(input) ?: throw RuntimeException("connection closed")
        }

        private fun sendRequest(output: OutputStream, method: String, uri: String, body: String, cseq: Int) {
            val msg = "$method $uri RTSP/1.0\r\n" +
                "CSeq: $cseq\r\n" +
                "User-Agent: MiraTest/1.0\r\n" +
                "Content-Type: text/parameters\r\n" +
                "Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n\r\n$body"
            output.write(msg.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }

        private fun sendResponse(output: OutputStream, cseq: String, code: Int, body: String, extra: String = "") {
            val msg = "RTSP/1.0 $code OK\r\n" +
                "CSeq: $cseq\r\n" +
                (if (extra.isNotEmpty()) "$extra\r\n" else "") +
                "Content-Type: text/parameters\r\n" +
                "Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n\r\n$body"
            output.write(msg.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }
    }
}

private fun String.paramValue(key: String): String? {
    val regex = Regex("(?:^|\\r?\\n)$key[=:]\\s?([^\\r\\n]*)")
    val m = regex.find(this)
    return m?.groupValues?.get(1)
}