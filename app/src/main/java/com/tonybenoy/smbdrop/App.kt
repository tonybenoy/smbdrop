package com.tonybenoy.smbdrop

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.tonybenoy.smbdrop.data.AppDb
import com.tonybenoy.smbdrop.data.Prefs
import com.tonybenoy.smbdrop.discovery.Discovery
import com.tonybenoy.smbdrop.smb.SmbPool
import com.tonybenoy.smbdrop.transfer.OfflineWorker
import com.tonybenoy.smbdrop.transfer.Scheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class App : Application() {
    lateinit var db: AppDb
    lateinit var prefs: Prefs
    val pool = SmbPool()
    lateinit var discovery: Discovery
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDb.build(this)
        prefs = Prefs(this)
        discovery = Discovery(this)
        createChannels()
        scope.launch {
            // Anything left ACTIVE from a killed process goes back to the queue.
            db.transfers().resetActive()
            Scheduler.kick(this@App)
            Scheduler.schedulePeriodicBackup(this@App)
            OfflineWorker.schedule(this@App)
        }
        // Wake "waiting for network" items whenever the network changes.
        discovery.networkChanges().onEach {
            pool.dropAll()
            db.transfers().wakeWaiting()
            Scheduler.kick(this)
        }.launchIn(scope)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Transfers in progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Completed", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ERROR, "Errors", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_BACKUP, "Backup summaries", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        lateinit var instance: App
        const val CH_PROGRESS = "progress"; const val CH_DONE = "done"; const val CH_ERROR = "error"; const val CH_BACKUP = "backup"
    }
}

val Application.app get() = this as App
