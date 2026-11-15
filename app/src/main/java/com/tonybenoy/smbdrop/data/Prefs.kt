package com.tonybenoy.smbdrop.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

data class Settings(
    val theme: String = "system",          // system | light | dark
    val glass: String = "full",            // full | reduced | off
    val concurrency: Int = 2,
    val defaultDestination: Long = 0,
    val askEveryTime: Boolean = false,
    val allowMobileData: Boolean = false,
    val biometricLock: Boolean = false,
    val verifyHash: Boolean = false,
    val notifyProgress: Boolean = true,
    val notifyDone: Boolean = true,
    val notifyErrors: Boolean = true,
    val notifyBackup: Boolean = true,
    val pausedUntil: Long = 0,
    val onboarded: Boolean = false,
    val wifiNames: String = "",            // comma separated SSIDs allowed for backup ("" = any)
    val nightOnly: Boolean = false,
    val minBattery: Int = 0,
    val tutorialSeen: Boolean = false,
    val language: String = "system",
    val backupStart: Int = 22 * 60,        // minutes after midnight
    val backupEnd: Int = 7 * 60,
    val windowOverrideUntil: Long = 0,
    val backupDays: Int = 127,             // bit 0 = Monday … bit 6 = Sunday
    val hideBatteryBanner: Boolean = false,
)

class Prefs(private val c: Context) {
    private object K {
        val theme = stringPreferencesKey("theme"); val glass = stringPreferencesKey("glass")
        val conc = intPreferencesKey("conc"); val defDest = longPreferencesKey("defDest")
        val ask = booleanPreferencesKey("ask"); val mobile = booleanPreferencesKey("mobile")
        val bio = booleanPreferencesKey("bio"); val hash = booleanPreferencesKey("hash")
        val nP = booleanPreferencesKey("nP"); val nD = booleanPreferencesKey("nD")
        val nE = booleanPreferencesKey("nE"); val nB = booleanPreferencesKey("nB")
        val paused = longPreferencesKey("paused"); val onb = booleanPreferencesKey("onb")
        val wifi = stringPreferencesKey("wifi"); val night = booleanPreferencesKey("night")
        val bat = intPreferencesKey("bat"); val tut = booleanPreferencesKey("tut"); val lang = stringPreferencesKey("lang"); val wStart = intPreferencesKey("wStart"); val wEnd = intPreferencesKey("wEnd"); val wOver = longPreferencesKey("wOver"); val wDays = intPreferencesKey("wDays"); val hideBatt = booleanPreferencesKey("hideBatt")
    }

    val flow: Flow<Settings> = c.store.data.map { p ->
        Settings(
            p[K.theme] ?: "system", p[K.glass] ?: "full", p[K.conc] ?: 2, p[K.defDest] ?: 0,
            p[K.ask] ?: false, p[K.mobile] ?: false, p[K.bio] ?: false, p[K.hash] ?: false,
            p[K.nP] ?: true, p[K.nD] ?: true, p[K.nE] ?: true, p[K.nB] ?: true,
            p[K.paused] ?: 0, p[K.onb] ?: false, p[K.wifi] ?: "", p[K.night] ?: false, p[K.bat] ?: 0, p[K.tut] ?: false, p[K.lang] ?: "system", p[K.wStart] ?: (22 * 60), p[K.wEnd] ?: (7 * 60), p[K.wOver] ?: 0, p[K.wDays] ?: 127, p[K.hideBatt] ?: false,
        )
    }

    suspend fun get(): Settings = flow.first()

    suspend fun setTheme(v: String) = c.store.edit { it[K.theme] = v }
    suspend fun setGlass(v: String) = c.store.edit { it[K.glass] = v }
    suspend fun setConcurrency(v: Int) = c.store.edit { it[K.conc] = v.coerceIn(1, 4) }
    suspend fun setDefaultDestination(v: Long) = c.store.edit { it[K.defDest] = v }
    suspend fun setAsk(v: Boolean) = c.store.edit { it[K.ask] = v }
    suspend fun setMobile(v: Boolean) = c.store.edit { it[K.mobile] = v }
    suspend fun setBiometric(v: Boolean) = c.store.edit { it[K.bio] = v }
    suspend fun setVerifyHash(v: Boolean) = c.store.edit { it[K.hash] = v }
    suspend fun setNotify(progress: Boolean? = null, done: Boolean? = null, err: Boolean? = null, backup: Boolean? = null) =
        c.store.edit { p ->
            progress?.let { p[K.nP] = it }; done?.let { p[K.nD] = it }
            err?.let { p[K.nE] = it }; backup?.let { p[K.nB] = it }
        }
    suspend fun setPausedUntil(v: Long) = c.store.edit { it[K.paused] = v }
    suspend fun setOnboarded(v: Boolean) = c.store.edit { it[K.onb] = v }
    suspend fun setWifiNames(v: String) = c.store.edit { it[K.wifi] = v }
    suspend fun setNightOnly(v: Boolean) = c.store.edit { it[K.night] = v }
    suspend fun setTutorialSeen(v: Boolean) = c.store.edit { it[K.tut] = v }
    suspend fun setBackupWindow(start: Int, end: Int) = c.store.edit { it[K.wStart] = start; it[K.wEnd] = end }
    suspend fun setHideBatteryBanner(v: Boolean) = c.store.edit { it[K.hideBatt] = v }
    suspend fun setBackupDays(v: Int) = c.store.edit { it[K.wDays] = v }
    suspend fun setWindowOverrideUntil(v: Long) = c.store.edit { it[K.wOver] = v }
    suspend fun setLanguage(v: String) = c.store.edit { it[K.lang] = v }
    suspend fun setMinBattery(v: Int) = c.store.edit { it[K.bat] = v }
}
