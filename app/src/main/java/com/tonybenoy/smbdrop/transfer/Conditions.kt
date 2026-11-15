package com.tonybenoy.smbdrop.transfer

import com.tonybenoy.smbdrop.ui.tr
import com.tonybenoy.smbdrop.ui.trf

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import com.tonybenoy.smbdrop.data.Settings
import java.util.Calendar

/** The single "can this run right now" check, shared by transfers and backup. */
object Conditions {
    fun onUnmetered(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun hasNetwork(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun onWifi(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    @Suppress("DEPRECATION")
    fun currentSsid(c: Context): String? = try {
        c.applicationContext.getSystemService(WifiManager::class.java).connectionInfo?.ssid?.trim('"')
            ?.takeIf { it != "<unknown ssid>" && it.isNotBlank() }
    } catch (e: Exception) { null }

    fun charging(c: Context): Boolean {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val s = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
    }

    fun batteryPct(c: Context): Int {
        val bm = c.getSystemService(BatteryManager::class.java)
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    fun dataSaver(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        return cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }

    fun powerSave(c: Context) = c.getSystemService(PowerManager::class.java).isPowerSaveMode

    /** True if now is inside [startMin, endMin) minutes-after-midnight; handles windows that cross midnight. */
    fun inWindow(startMin: Int, endMin: Int, nowMin: Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }): Boolean =
        if (startMin == endMin) true else if (startMin < endMin) nowMin in startMin until endMin else nowMin >= startMin || nowMin < endMin

    /** Day-of-week bit for a Calendar day (Mon = bit 0). A window that crosses midnight belongs to the day it starts on. */
    fun dayAllowed(days: Int, startMin: Int, endMin: Int): Boolean {
        val c = Calendar.getInstance()
        val nowMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        var d = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7           // Mon=0 … Sun=6
        if (startMin > endMin && nowMin < endMin) d = (d + 6) % 7  // after midnight: still yesterday's window
        return days and (1 shl d) != 0
    }

    fun fmtMin(m: Int): String = "%02d:%02d".format(m / 60, m % 60)

    /** Why a transfer cannot run now, or null if it can. */
    fun blocker(c: Context, s: Settings, mobileOk: Boolean, needsCharging: Boolean, backup: Boolean): String? {
        if (s.pausedUntil > System.currentTimeMillis()) return tr("Paused")
        if (!hasNetwork(c)) return tr("Waiting for network")
        val wifi = onWifi(c)
        if (!wifi && !(mobileOk || s.allowMobileData)) return tr("Waiting for Wi-Fi")
        if (!wifi && dataSaver(c) && !mobileOk) return tr("Data Saver is on")
        if (needsCharging && !charging(c)) return tr("Waiting for charger")
        if (backup) {
            if (s.nightOnly && System.currentTimeMillis() > s.windowOverrideUntil && (!inWindow(s.backupStart, s.backupEnd) || !dayAllowed(s.backupDays, s.backupStart, s.backupEnd))) return trf("Waiting for backup window (%1\$s–%2\$s)", fmtMin(s.backupStart), fmtMin(s.backupEnd))
            if (s.minBattery > 0 && !charging(c) && batteryPct(c) < s.minBattery) return trf("Battery below %1\$d%%", s.minBattery)
            if (s.wifiNames.isNotBlank()) {
                val ssid = currentSsid(c)
                val allowed = s.wifiNames.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (ssid != null && ssid !in allowed) return tr("Not on a chosen Wi-Fi")
            }
        }
        return null
    }
}
