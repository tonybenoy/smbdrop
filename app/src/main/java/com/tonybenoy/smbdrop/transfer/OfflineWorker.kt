package com.tonybenoy.smbdrop.transfer

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.tonybenoy.smbdrop.App
import com.tonybenoy.smbdrop.data.OfflineItem
import com.tonybenoy.smbdrop.smb.RemoteEntry
import com.tonybenoy.smbdrop.smb.SmbError
import com.tonybenoy.smbdrop.smb.toSmbError
import com.tonybenoy.smbdrop.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Keeps pinned files and folders available offline: one-way server → phone, only what changed. */
class OfflineWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val app = ctx.applicationContext as App

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = NotificationCompat.Builder(ctx, App.CH_PROGRESS).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("SMB Drop").setContentText(tr("Syncing offline files…")).setOngoing(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(4, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(4, n)
    }

    override suspend fun doWork(): Result {
        val s = app.prefs.get()
        if (Conditions.blocker(ctx, s, false, false, false) != null) return Result.retry()
        runCatching { setForeground(getForegroundInfo()) }
        for (item in app.db.offline().list()) {
            val dest = app.db.destinations().get(item.destinationId) ?: continue
            try {
                val bytes = withContext(Dispatchers.IO) {
                    app.pool.use(dest) { conn ->
                        val root = localRoot(ctx, item.destinationId, item.path)
                        if (item.isDir) syncDir(conn, item.path, root) else {
                            val parent = item.path.substringBeforeLast('/', "")
                            val e = conn.list(parent).firstOrNull { it.path == item.path } ?: throw SmbError.Gone()
                            syncFile(conn, e, root)
                        }
                        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    }
                }
                app.db.offline().update(item.copy(lastSync = System.currentTimeMillis(), bytes = bytes, error = null))
            } catch (e: Exception) {
                app.db.offline().update(item.copy(error = e.toSmbError().userMessage))
            }
        }
        return Result.success()
    }

    private fun syncDir(conn: com.tonybenoy.smbdrop.smb.SmbConn, remote: String, local: File) {
        local.mkdirs()
        val entries = conn.list(remote)
        val names = entries.map { it.name }.toSet()
        local.listFiles()?.filter { it.name !in names }?.forEach { it.deleteRecursively() }   // removed on the server
        for (e in entries) {
            if (e.isDir) syncDir(conn, e.path, File(local, e.name)) else syncFile(conn, e, File(local, e.name))
        }
    }

    private fun syncFile(conn: com.tonybenoy.smbdrop.smb.SmbConn, e: RemoteEntry, target: File) {
        if (target.isFile && target.length() == e.size && kotlin.math.abs(target.lastModified() - e.modified) < 2000) return
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        conn.openRead(e.path).use { f ->
            tmp.outputStream().use { o ->
                val buf = ByteArray(1 shl 20); var off = 0L
                while (true) { val n = f.read(buf, off, 0, buf.size); if (n <= 0) break; o.write(buf, 0, n); off += n }
            }
        }
        target.delete(); tmp.renameTo(target); target.setLastModified(e.modified)
    }

    companion object {
        fun localRoot(c: Context, destId: Long, path: String) = File(File(c.filesDir, "offline"), "$destId/${path.trim('/')}")

        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<OfflineWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("offline-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }
        fun syncNow(c: Context) {
            val req = OneTimeWorkRequestBuilder<OfflineWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(c).enqueueUniqueWork("offline-now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
