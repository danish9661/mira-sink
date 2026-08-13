package com.mira.sink

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.mira.sink.p2p.P2pController
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Diagnostics {

    fun buildReport(context: Context, p2p: P2pController?): String {
        val sb = StringBuilder()
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        sb.appendLine("==== Mira Sink diagnostic report ====")
        sb.appendLine("Generated: $ts")
        sb.appendLine()

        sb.appendLine("---- App ----")
        try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            sb.appendLine("versionName: ${info.versionName}")
            sb.appendLine("versionCode: ${info.versionCode}")
            sb.appendLine("targetSdk: ${info.applicationInfo?.targetSdkVersion}")
        } catch (t: Throwable) {
            sb.appendLine("app info error: ${t.message}")
        }
        sb.appendLine()

        sb.appendLine("---- Device ----")
        sb.appendLine("manufacturer: ${Build.MANUFACTURER}")
        sb.appendLine("model: ${Build.MODEL}")
        sb.appendLine("device: ${Build.DEVICE}")
        sb.appendLine("product: ${Build.PRODUCT}")
        sb.appendLine("androidVersion: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("securityPatch: ${Build.VERSION.SECURITY_PATCH}")
        sb.appendLine("fingerprint: ${Build.FINGERPRINT}")
        sb.appendLine()

        sb.appendLine("---- Permissions ----")
        val perms = listOf(
            "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"
        )
        for (p in perms) {
            val r = try {
                context.checkSelfPermission(p)
            } catch (t: Throwable) {
                -2
            }
            sb.appendLine("$p: ${
                when (r) {
                    PackageManager.PERMISSION_GRANTED -> "granted"
                    PackageManager.PERMISSION_DENIED -> "denied"
                    else -> "n/a"
                }
            }")
        }
        sb.appendLine()

        sb.appendLine("---- System settings ----")
        sb.appendLine("canWriteSettings: ${Settings.System.canWrite(context)}")
        sb.appendLine("shizukuInstalled: ${WfdEnabler.shizukuInstalled()}")
        sb.appendLine("shizukuReady: ${WfdEnabler.shizukuReady()}")
        sb.appendLine("wifi_display_on: ${WfdEnabler.currentValue(context)}")
        sb.appendLine()

        sb.appendLine("---- WiFi ----")
        try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            sb.appendLine("wifiEnabled: ${wm.isWifiEnabled}")
            sb.appendLine("wifiConnectionInfo: ${wm.connectionInfo}")
        } catch (t: Throwable) {
            sb.appendLine("wifi info error: ${t.message}")
        }
        sb.appendLine()

        sb.appendLine("---- P2P state ----")
        if (p2p == null) {
            sb.appendLine("(service not bound)")
        } else {
            sb.appendLine("p2pEnabled: ${p2p.p2pEnabled}")
            sb.appendLine("channelLost: ${p2p.channelLost}")
            sb.appendLine("groupCreated: ${p2p.groupCreated}")
            sb.appendLine("ssid: ${p2p.ssid}")
            sb.appendLine("passphrase: ${p2p.passphrase}")
            sb.appendLine("interface: ${p2p.interfaceName}")
            sb.appendLine("wfdInfoAttached: ${p2p.wfdInfoAttached}")
            sb.appendLine("wfdInfoError: ${p2p.wfdInfoError}")
            sb.appendLine("lastGroupFailure: ${p2p.lastGroupFailure}")
            sb.appendLine("p2pInterfaceIps: ${interfaceIps(p2p.interfaceName)}")
        }
        sb.appendLine()

        sb.appendLine("---- Current state ----")
        sb.appendLine(StatusBus.state.toString())
        sb.appendLine()

        sb.appendLine("---- Event log tail ----")
        val tail = StatusBus.logTail()
        if (tail.isEmpty()) {
            sb.appendLine("(empty)")
        } else {
            for (line in tail) sb.appendLine(line)
        }
        sb.appendLine()

        sb.appendLine("---- Recent logcat (own pid) ----")
        try {
            val pid = android.os.Process.myPid()
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "--pid=$pid", "-t", "300"))
            p.inputStream.bufferedReader().useLines { lines ->
                var n = 0
                for (l in lines) {
                    if (n++ >= 300) break
                    sb.appendLine(l)
                }
            }
            p.waitFor()
        } catch (t: Throwable) {
            sb.appendLine("logcat unavailable: ${t.message}")
        }

        sb.appendLine()
        sb.appendLine("==== End of report ====")
        return sb.toString()
    }

    private fun safeSetting(context: Context, key: String): String {
        return try {
            Settings.Global.getString(context.contentResolver, key) ?: "(null)"
        } catch (t: Throwable) {
            "error: ${t.message}"
        }
    }

    private fun interfaceIps(iface: String): String {
        if (iface.isEmpty()) return "(no interface)"
        return try {
            val nif = NetworkInterface.getByName(iface) ?: return "(not found)"
            nif.inetAddresses.toList().joinToString(", ") { it.hostAddress ?: "?" }
        } catch (t: Throwable) {
            "error: ${t.message}"
        }
    }
}
