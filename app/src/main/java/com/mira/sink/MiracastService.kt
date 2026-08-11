package com.mira.sink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import com.mira.sink.codec.VideoDecoder
import com.mira.sink.p2p.P2pController
import com.mira.sink.rtsp.RtspConnection
import com.mira.sink.rtsp.RtspListener
import com.mira.sink.rtsp.RtspServer
import com.mira.sink.uibc.UibcServer
import com.mira.sink.udp.RtpReceiver
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class MiracastService : Service() {

    companion object {
        private const val TAG = "MiraService"
        private const val CHANNEL_ID = "mira_sink"
        private const val NOTIF_ID = 1
        private const val RTP_PORT = 1550
        private const val UIBC_PORT = 7237
    }

    inner class MiraBinder : Binder() {
        val service: MiracastService get() = this@MiracastService
    }

    private val binder = MiraBinder()

    private lateinit var p2p: P2pController
    private lateinit var rtspServer: RtspServer
    val decoder = VideoDecoder()
    private val rtp = RtpReceiver(decoder)
    private lateinit var uibc: UibcServer

    private val started = AtomicBoolean(false)
    @Volatile
    private var currentSession: RtspConnection? = null
    private val sessions = Collections.synchronizedSet(mutableSetOf<RtspConnection>())

    @Volatile
    private var startIntent: Intent? = null

    private var wakeLock: PowerManager.WakeLock? = null

    private val rtspListener = object : RtspListener {
        override fun onRtspConnectionOpened(conn: RtspConnection) {
            sessions.add(conn)
            currentSession = conn
            Thread(conn, "rtsp-session").start()
        }

        override fun onRtspClosed() {
            Log.i(TAG, "RTSP session closed, stopping receiver")
            rtp.stop()
            rtp.resetSink()
            StatusBus.update(SinkState(Phase.WAITING_CAST, "Source disconnected — waiting…", ""))
        }

        override fun bindRtpPort(): Int {
            val port = rtp.bind(RTP_PORT)
            StatusBus.log("RTP listening on UDP $port")
            return port
        }

        override fun startStreaming() {
            Log.i(TAG, "Streaming start")
            rtp.bindRtcp(rtp.boundPort + 1)
            rtp.start()
            acquireWakelock()
            StatusBus.update(
                StatusBus.state.copy(phase = Phase.STREAMING, message = "Streaming", detail = "")
            )
        }

        override fun setUibcEnabled(enabled: Boolean) {
            if (enabled) {
                uibc.start()
                StatusBus.update(
                    StatusBus.state.copy(uibcActive = true, message = "UIBC enabled")
                )
            } else {
                uibc.stop()
                StatusBus.update(
                    StatusBus.state.copy(uibcActive = false)
                )
            }
        }

        override fun onVideoFormatsChosen(value: String) {
            StatusBus.log("Source chose video format: ${value.take(120)}")
            val first = value.trim().substringBefore(' ').toIntOrNull()
            val isH265 = first != null && first == 0
            val label = if (isH265) "H.265" else "H.264"
            Log.i(TAG, "Negotiated codec: $label (entry=$first)")
            rtp.setCodecH265(isH265)
        }

        override fun onRtspOptionsReceived() {
            Log.i(TAG, "RTSP options received")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        p2p = P2pController(this)
        rtspServer = RtspServer(rtspListener)
        uibc = UibcServer(UIBC_PORT)
        startForegroundCompat()
        rtspServer.start()
        p2p.initialize()
        p2p.ensureGroup()
        acquireWakelock()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "Mira Sink", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Mira Sink")
            .setContentText("Wi-Fi Direct group: ${p2p.ssid.ifEmpty { "starting…" }}")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun acquireWakelock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mira:stream")
                .apply { setReferenceCounted(false) }
        }
        wakeLock?.acquire()
    }

    private fun releaseWakelock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startIntent = intent
        decoder.setBufferMode(intent?.getBooleanExtra("mira.bufferMode", false) == true)
        decoder.setImageReaderMode(intent?.getBooleanExtra("mira.imageReader", false) == true)
        decoder.setCaptureEnabled(intent?.getBooleanExtra("mira.capture", false) == true)
        decoder.setLocalMode(intent?.getBooleanExtra("mira.local", false) == true)
        decoder.setMinimalConfig(intent?.getBooleanExtra("mira.minimal", false) == true)
        decoder.setWholeFileMode(intent?.getBooleanExtra("mira.whole", false) == true)
        return START_NOT_STICKY
    }

    fun provideSurface(surface: Surface?) {
        decoder.setSurface(surface)
    }

    fun getDecoderSurface(): Surface? = decoder.getSurface()

    fun uiibcServer(): UibcServer = uibc

    fun resetSession() {
        Log.i(TAG, "Reset requested")
        rtp.stop()
        rtp.resetSink()
        decoder.release()
        uibc.stop()
        sessions.toList().forEach { it.close() }
        sessions.clear()
        p2p.removeGroup()
        StatusBus.update(SinkState(Phase.P2P_SETUP, "Resetting…", ""))
        p2p.ensureGroup()
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroy")
        started.set(false)
        rtp.stop()
        rtp.resetSink()
        decoder.release()
        uibc.stop()
        rtspServer.stop()
        sessions.toList().forEach { it.close() }
        p2p.destroy()
        releaseWakelock()
        super.onDestroy()
    }
}