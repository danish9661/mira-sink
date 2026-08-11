package com.mira.sink.udp

import android.util.Log
import com.mira.sink.codec.VideoDecoder
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

class RtpReceiver(private val decoder: VideoDecoder) {

    companion object {
        private const val TAG = "MiraUDP"
        private const val BUF_SIZE = 65536
    }

    private val packetBuf = ByteArray(BUF_SIZE)
    private val depacketizer = Depacketizer(object : Depacketizer.Sink {
        override fun onAccessUnit(au: ByteArray, offset: Int, length: Int, isH265: Boolean) {
            decoder.feed(au, offset, length, isH265, lastArrivalNanos)
        }
    })

    @Volatile
    private var lastArrivalNanos = 0L

    private var statsWindowStart = 0L
    private var statsPackets = 0L

    private fun logStats(s: DatagramSocket) {
        if (statsWindowStart == 0L) statsWindowStart = System.nanoTime()
        val now = System.nanoTime()
        statsPackets++
        if (now - statsWindowStart >= 2_000_000_000L) {
            val pktsPerSec = statsPackets * 1_000_000_000.0 / (now - statsWindowStart)
            Log.i(
                TAG,
                "traffic: pkts/s=${"%.1f".format(pktsPerSec)} frames=${decoder.framesQueuedTotal()} rendered=${decoder.framesRenderedTotal()}"
            )
            statsPackets = 0
            statsWindowStart = now
        }
    }

    @Volatile
    private var socket: DatagramSocket? = null

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var rtcpThread: Thread? = null

    @Volatile
    var boundPort: Int = 0
        private set

    @Volatile
    var started = false
        private set

    fun bind(preferredPort: Int): Int {
        synchronized(this) {
            if (socket != null) return boundPort
            var port = preferredPort
            while (port < preferredPort + 8) {
                try {
                    val s = DatagramSocket(port)
                    s.reuseAddress = true
                    socket = s
                    boundPort = port
                    Log.i(TAG, "RTP socket bound on $boundPort")
                    return boundPort
                } catch (e: SocketException) {
                    port += 2
                }
            }
            try {
                val s = DatagramSocket()
                socket = s
                boundPort = s.localPort
                Log.i(TAG, "RTP socket bound on ephemeral $boundPort")
                return boundPort
            } catch (e: IOException) {
                Log.e(TAG, "Failed to bind any RTP socket", e)
                return 0
            }
        }
    }

    fun start() {
        synchronized(this) {
            if (started) return
            val s = socket ?: return
            started = true
            running.set(true)
            thread = Thread({ receiveLoop(s) }, "rtp-receiver")
            thread!!.priority = Thread.MAX_PRIORITY
            thread!!.start()
            Log.i(TAG, "RTP receiver started on port $boundPort")
        }
    }

    private fun receiveLoop(s: DatagramSocket) {
        while (running.get()) {
            try {
                val packet = DatagramPacket(packetBuf, packetBuf.size)
                s.receive(packet)
                lastArrivalNanos = System.nanoTime()
                logStats(s)
                if (packet.length < 12) continue
                depacketizer.handlePacket(packetBuf, 0, packet.length)
            } catch (e: SocketException) {
                if (running.get()) Log.w(TAG, "RTP receive error", e)
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "RTP I/O error", e)
            }
        }
    }

    fun bindRtcp(port: Int) {
        if (rtcpThread != null) return
        try {
            val s = DatagramSocket(port)
            rtcpThread = Thread({
                val b = ByteArray(2048)
                while (running.get()) {
                    try {
                        s.receive(DatagramPacket(b, b.size))
                    } catch (e: IOException) {
                        break
                    }
                }
            }, "rtcp-receiver")
            rtcpThread!!.start()
        } catch (t: Throwable) {
            Log.w(TAG, "RTCP channel not bound ($port)")
        }
    }

    fun resetSink() {
        depacketizer.reset()
    }

    fun setCodecH265(h265: Boolean?) {
        depacketizer.setCodecH265(h265)
    }

    fun stop() {
        synchronized(this) {
            if (!started) return
            started = false
            running.set(false)
            try {
                socket?.close()
            } catch (t: Throwable) {
            }
            socket = null
            boundPort = 0
            thread?.join(1000)
            thread = null
            rtcpThread?.let { t ->
                t.interrupt()
                t.join(500)
            }
            rtcpThread = null
            Log.i(TAG, "RTP receiver stopped")
        }
    }
}