package com.mira.sink.p2p

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pWfdInfo
import android.os.Build
import android.os.HandlerThread
import android.util.Log
import com.mira.sink.Phase
import com.mira.sink.SinkState
import com.mira.sink.StatusBus
import java.lang.reflect.Method

class P2pController(private val context: Context) {

    companion object {
        private const val TAG = "MiraP2P"
        private const val GROUP_SSID = "DIRECT-Mira"
    }

    private val thread = HandlerThread("p2p-looper")
    private val manager: WifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager

    @Volatile
    var groupCreated = false
        private set

    @Volatile
    private var channel: WifiP2pManager.Channel? = null

    @Volatile
    private var registered = false

    private var groupSsid = ""
    private var groupPassphrase = ""
    private var groupInterface = ""

    val ssid: String get() = groupSsid
    val passphrase: String get() = groupPassphrase
    val interfaceName: String get() = groupInterface

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE, -1
                    ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.i(TAG, "P2P state changed: enabled=$enabled")
                    if (enabled && !groupCreated) ensureGroup()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val ch = channel ?: return
                    manager.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
                        if (info == null) return@requestConnectionInfo
                        Log.i(TAG, "connection: groupFormed=${info.groupFormed} isGO=${info.isGroupOwner}")
                        if (info.groupFormed && info.isGroupOwner) {
                            requestGroup()
                        } else if (info.groupFormed && !info.isGroupOwner) {
                            StatusBus.update(
                                SinkState(
                                    Phase.WAITING_CAST,
                                    "Joined an existing group as client",
                                    ""
                                )
                            )
                        } else {
                            groupCreated = false
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val device: WifiP2pDevice? =
                        if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(
                                WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                                WifiP2pDevice::class.java
                            )
                        } else {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        }
                    Log.i(TAG, "This device: $device")
                }
            }
        }
    }

    fun initialize() {
        thread.start()
        channel = manager.initialize(context, thread.looper) {
            Log.w(TAG, "P2P init channel lost")
        }
        if (!registered) {
            context.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
                }
            )
            registered = true
        }
        channel?.let { ch ->
            manager.requestP2pState(ch) { state ->
                Log.i(TAG, "Initial P2P state: $state")
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) ensureGroup()
            }
        }
    }

    fun ensureGroup() {
        val ch = channel ?: return
        if (groupCreated) return
        StatusBus.update(SinkState(Phase.P2P_SETUP, "Creating Wi-Fi Direct group…", ""))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val builder = WifiP2pConfig.Builder()
                builder.setNetworkName(GROUP_SSID)
                builder.setPassphrase(randomPassphrase())
                builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                val config = builder.build()
                config.groupOwnerIntent = 15
                attachWfdInfo(config)
                attachWpsPbc(config)
                manager.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.i(TAG, "createGroup(5GHz) success")
                        requestGroup()
                    }

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "createGroup(5GHz) failed reason=$reason, falling back to legacy")
                        StatusBus.log("5GHz group creation failed (reason=$reason), retrying on 2.4GHz")
                        createGroupLegacy()
                    }
                })
                return
            } catch (t: Throwable) {
                Log.w(TAG, "createGroup with 5GHz config failed, falling back to 2.4GHz", t)
                StatusBus.log("5GHz group creation failed: ${t.message}")
            }
        }
        createGroupLegacy()
    }

    private fun createGroupLegacy() {
        val ch = channel ?: return
        try {
            manager.createGroup(ch, actionListener("createGroup(legacy)"))
        } catch (t: Throwable) {
            Log.e(TAG, "createGroup failed", t)
            StatusBus.update(SinkState(Phase.ERROR, "P2P group creation failed", t.message ?: ""))
        }
    }

    private fun attachWfdInfo(config: WifiP2pConfig) {
        try {
            val constructor = WifiP2pWfdInfo::class.java.getConstructor(
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
            val wfd = constructor.newInstance(true, WifiP2pWfdInfo.DEVICE_TYPE_SECONDARY_SINK, true)
            val wfdType = WifiP2pWfdInfo::class.java
            config.javaClass.getMethod("setWfdInfo", wfdType).invoke(config, wfd)
            Log.i(TAG, "WFD info attached to group config")
        } catch (t: Throwable) {
            Log.w(TAG, "setWfdInfo unavailable on this build", t)
        }
    }

    private fun attachWpsPbc(config: WifiP2pConfig) {
        try {
            val wps = WpsInfo()
            wps.setup = WpsInfo.PBC
            config.wps = wps
        } catch (t: Throwable) {
            Log.w(TAG, "WPS PBC set failed", t)
        }
    }

    private fun actionListener(tag: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            Log.i(TAG, "$tag success")
            requestGroup()
        }

        override fun onFailure(reason: Int) {
            Log.e(TAG, "$tag failure reason=$reason")
            StatusBus.update(
                SinkState(
                    Phase.ERROR,
                    "P2P group failed",
                    "$tag reason=$reason"
                )
            )
        }
    }

    private fun requestGroup() {
        val ch = channel ?: return
        manager.requestGroupInfo(ch) { group: WifiP2pGroup? ->
            if (group == null) {
                Log.w(TAG, "requestGroupInfo null")
                return@requestGroupInfo
            }
            groupCreated = true
            groupSsid = group.networkName ?: ""
            groupPassphrase = group.passphrase ?: ""
            groupInterface = try {
                group.getInterface() ?: ""
            } catch (t: Throwable) {
                ""
            }
            Log.i(TAG, "Group ready: $groupSsid clients=${group.clientList.size}")
            StatusBus.update(
                SinkState(
                    Phase.GROUP_READY,
                    "Wi-Fi Direct group ready",
                    "SSID: $groupSsid  Key: $groupPassphrase"
                )
            )
        }
    }

    fun removeGroup() {
        groupCreated = false
        val ch = channel ?: return
        try {
            manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Group removed")
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "removeGroup failed: $reason")
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "removeGroup threw", t)
        }
    }

    fun destroy() {
        removeGroup()
        if (registered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (t: Throwable) {
            }
            registered = false
        }
        channel?.let { ch ->
            try {
                val close = WifiP2pManager::class.java.getMethod("close", WifiP2pManager.Channel::class.java)
                close.invoke(manager, ch)
            } catch (t: Throwable) {
                Log.w(TAG, "close channel unavailable", t)
            }
        }
        channel = null
        thread.quitSafely()
    }

    private fun randomPassphrase(): String {
        val chars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val sb = StringBuilder(8)
        for (i in 0 until 8) sb.append(chars[(Math.random() * chars.length).toInt()])
        return sb.toString()
    }
}