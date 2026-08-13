package com.mira.sink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.ContentValues
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.mira.sink.uibc.TouchOverlayView

class MainActivity : Activity(), StatusListener {

    companion object {
        private const val REQ_PERMS = 1001
    }

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var detailsText: TextView
    private lateinit var surfaceView: TextureView
    private lateinit var touchOverlay: TouchOverlayView

    private var service: MiracastService? = null
    private var bound = false

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
            val s = Surface(st)
            service?.provideSurface(s)
            if (service == null) {
                pendingSurface = s
            }
            st.setDefaultBufferSize(1920, 1080)
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            service?.provideSurface(null)
            pendingSurface?.let { it.release() }
            pendingSurface = null
            return true
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    private var pendingSurface: Surface? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as MiracastService.MiraBinder).service
            StatusBus.log("Service connected")
            touchOverlay.uibc = service?.uiibcServer()
            service?.let { s ->
                if (s.getDecoderSurface() == null) {
                    if (pendingSurface != null) {
                        s.provideSurface(pendingSurface)
                    } else if (surfaceView.isAvailable) {
                        s.provideSurface(Surface(surfaceView.surfaceTexture))
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        detailsText = findViewById(R.id.detailsText)
        surfaceView = findViewById(R.id.videoSurface)
        touchOverlay = findViewById(R.id.touchOverlay)

        surfaceView.surfaceTextureListener = surfaceTextureListener

        findViewById<Button>(R.id.resetButton).setOnClickListener {
            service?.resetSession()
        }

        findViewById<Button>(R.id.reportButton).setOnClickListener {
            generateReport()
        }

        findViewById<Button>(R.id.wfdButton).setOnClickListener {
            requestWfdEnable()
        }

        StatusBus.addListener(this)
        requestRuntimePermissions()
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_PERMS)
        }
    }

    @SuppressLint("MissingSuperCall")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQ_PERMS) {
            StatusBus.log("Permissions: " +
                permissions.zip(grantResults.toList())
                    .joinToString { "${it.first}: ${if (it.second == 0) "granted" else "denied"}" })
        }
    }

    private fun requestWfdEnable() {
        WfdEnabler.enable(this) { ok, msg ->
            runOnUiThread {
                StatusBus.log("WFD enable: $msg")
                when {
                    ok -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    msg == "no-route" -> promptWfdRoute()
                    else -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun promptWfdRoute() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Enable Wi-Fi Display")
            .setMessage(
                "This phone's firmware blocks apps from enabling Wi-Fi Display advertisement directly.\n\n" +
                "Choose a way:\n" +
                "1) Shizuku (recommended) — install Shizuku from Play Store, start it once " +
                "(pairing via Wireless debugging — no PC needed), then tap this button again.\n" +
                "2) Grant 'Modify system settings' — may work on some phones.\n\n" +
                "Or quick test without any install: toggle Smart View / Screen sharing ON from " +
                "the quick settings and check if Windows sees the tablet."
            )
            .setPositiveButton("Grant system settings") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private var pendingWfdEnable = false

    override fun onResume() {
        super.onResume()
        if (pendingWfdEnable && Settings.System.canWrite(this)) {
            pendingWfdEnable = false
            WfdEnabler.enable(this) { ok, msg ->
                runOnUiThread {
                    StatusBus.log("WFD enable: $msg")
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun generateReport() {
        val report = Diagnostics.buildReport(this, service?.p2pController())
        Thread {
            try {
                val uri = saveReport(report)
                runOnUiThread {
                    Toast.makeText(this, "Report saved, sharing…", Toast.LENGTH_SHORT).show()
                    shareReport(uri)
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Report failed: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun saveReport(report: String): Uri {
        val name = "mira_report_${System.currentTimeMillis()}.txt"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Mira")
        }
        val resolver = contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) }
            ?: throw IllegalStateException("openOutputStream failed")
        return uri
    }

    private fun shareReport(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_SUBJECT, "Mira Sink diagnostic report")
        }
        startActivity(Intent.createChooser(send, "Share diagnostic report"))
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, MiracastService::class.java)
        val irMode = getIntent().getBooleanExtra("mira.imageReader", false)
        intent.putExtra("mira.bufferMode", getIntent().getBooleanExtra("mira.bufferMode", false) || irMode)
        intent.putExtra("mira.imageReader", irMode)
        intent.putExtra("mira.capture", getIntent().getBooleanExtra("mira.capture", false))
        intent.putExtra("mira.local", getIntent().getBooleanExtra("mira.local", false))
        intent.putExtra("mira.minimal", getIntent().getBooleanExtra("mira.minimal", false))
        intent.putExtra("mira.whole", getIntent().getBooleanExtra("mira.whole", false))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (bound) {
            unbindService(connection)
            bound = false
            service = null
        }
        super.onStop()
    }

    override fun onDestroy() {
        StatusBus.removeListener(this)
        super.onDestroy()
    }

    override fun onStateChanged(state: SinkState) {
        val c = this
        statusDot.post {
            when (state.phase) {
                Phase.IDLE, Phase.P2P_SETUP -> statusDot.setBackgroundColor(Color.parseColor("#FFC107"))
                Phase.GROUP_READY, Phase.WAITING_CAST -> statusDot.setBackgroundColor(Color.parseColor("#2196F3"))
                Phase.RTSP_NEGOTIATING -> statusDot.setBackgroundColor(Color.parseColor("#FF9800"))
                Phase.STREAMING -> statusDot.setBackgroundColor(Color.parseColor("#4CAF50"))
                Phase.ERROR -> statusDot.setBackgroundColor(Color.parseColor("#F44336"))
            }
            statusText.text = state.message
            detailsText.text = state.detail
            touchOverlay.visibility = if (state.uibcActive && state.phase == Phase.STREAMING) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
    }

    override fun onLog(line: String) {
        // detail line is reflected via state; logs go to logcat
    }
}