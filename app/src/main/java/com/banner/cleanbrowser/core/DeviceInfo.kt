package com.banner.cleanbrowser.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs

/** Read on the device only; nothing is sent anywhere. */
data class DeviceInfo(
    val model: String,
    val android: String,
    val battery: Int,
    val charging: Boolean,
    val storageFree: Long,
    val storageTotal: Long,
    val ramFree: Long,
    val ramTotal: Long,
    val network: String,
) {
    companion object {
        fun read(ctx: Context): DeviceInfo {
            val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val st = runCatching { StatFs(Environment.getExternalStorageDirectory().absolutePath) }.getOrNull()
            val am = ctx.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val net = when {
                caps == null -> "Offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Online"
            }
            return DeviceInfo(
                model = Build.MODEL,
                android = Build.VERSION.RELEASE,
                battery = if (level >= 0 && scale > 0) level * 100 / scale else -1,
                charging = charging,
                storageFree = st?.availableBytes ?: 0,
                storageTotal = st?.totalBytes ?: 0,
                ramFree = mi.availMem,
                ramTotal = mi.totalMem,
                network = net,
            )
        }
    }
}
