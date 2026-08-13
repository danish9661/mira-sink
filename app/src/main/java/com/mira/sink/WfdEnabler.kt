package com.mira.sink

import android.content.Context
import android.provider.Settings
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

object WfdEnabler {

    private const val KEY = "wifi_display_on"
    private val running = AtomicBoolean(false)

    fun currentValue(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, KEY, -1)

    fun isEnabled(context: Context): Boolean = currentValue(context) == 1

    fun shizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    fun shizukuInstalled(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
    }

    fun enable(context: Context, onResult: (Boolean, String) -> Unit) {
        if (!running.compareAndSet(false, true)) {
            onResult(false, "Already running…")
            return
        }
        Thread {
            try {
                val outcome = enableBlocking(context)
                running.set(false)
                onResult(outcome.first, outcome.second)
            } catch (t: Throwable) {
                running.set(false)
                onResult(false, "Failed: ${t.message}")
            }
        }.start()
    }

    private fun enableBlocking(context: Context): Pair<Boolean, String> {
        if (isEnabled(context)) return true to "Already enabled (wifi_display_on=1)"

        if (Settings.System.canWrite(context)) {
            val ok = try {
                Settings.Global.putInt(context.contentResolver, KEY, 1)
                isEnabled(context)
            } catch (t: Throwable) {
                false
            }
            if (ok) return true to "Enabled via WRITE_SETTINGS (wifi_display_on=1)"
        }

        if (shizukuReady()) {
            return runShell()
        }

        return false to "no-route"
    }

    private fun runShell(): Pair<Boolean, String> {
        val put = Shizuku.newProcess(
            arrayOf("settings", "put", "global", KEY, "1"),
            null, null
        )
        val exit = put.waitFor()
        val stderr = put.errorStream.bufferedReader().readText().trim()
        val get = Shizuku.newProcess(
            arrayOf("settings", "get", "global", KEY),
            null, null
        )
        get.waitFor()
        val value = get.inputStream.bufferedReader().readText().trim()
        if (exit == 0 && value == "1") {
            return true to "Enabled via Shizuku (wifi_display_on=1)"
        }
        return false to "Shizuku shell wrote exit=$exit value='$value' err='$stderr'"
    }
}