package com.tonybenoy.smbdrop.backup

import com.tonybenoy.smbdrop.ui.tr
import com.tonybenoy.smbdrop.ui.trf

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tonybenoy.smbdrop.App
import com.tonybenoy.smbdrop.data.*
import com.tonybenoy.smbdrop.routing.*
import com.tonybenoy.smbdrop.transfer.Conditions
import com.tonybenoy.smbdrop.transfer.Scheduler

class BackupWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val app = ctx.applicationContext as App
    private val BATCH = 500

    override suspend fun doWork(): Result {
        val settings = app.prefs.get()
        if (settings.pausedUntil > System.currentTimeMillis()) return Result.success()
        var total = 0
        for (src in app.db.backups().enabled()) {
            if (Conditions.blocker(ctx, settings, false, src.chargingOnly, true) != null && src.wifiOnly) continue
            total += scan(src)
        }
        if (total > 0) {
            Scheduler.kick(ctx)
            if (settings.notifyBackup) {
                val n = NotificationCompat.Builder(ctx, App.CH_BACKUP).setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setContentTitle(tr("Backup")).setContentText(trf("%1\$d new files queued", total)).setAutoCancel(true).build()
                runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).notify(3, n) }
            }
        }
        return Result.success()
    }

    private data class Candidate(val uri: Uri, val key: String, val name: String, val size: Long, val mime: String?, val modified: Long)

    private fun mediaCandidates(src: BackupSource): List<Candidate> {
        val out = ArrayList<Candidate>()
        val rel = if (src.type == "SCREENSHOTS") listOf("%Screenshots%") else listOf("DCIM/%")
        for ((coll, isVideo) in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false, MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true)) {
            val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_ADDED)
            val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DATE_ADDED} > ? AND ${MediaStore.MediaColumns.IS_PENDING}=0"
            runCatching {
                ctx.contentResolver.query(coll, proj, sel, arrayOf(rel[0], src.onlyNewFrom.toString()), "${MediaStore.MediaColumns.DATE_ADDED} ASC")?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        out += Candidate(ContentUris.withAppendedId(coll, id), "media:${if (isVideo) "v" else "i"}$id", c.getString(1) ?: "file",
                            c.getLong(2), c.getString(3), c.getLong(4) * 1000)
                    }
                }
            }
        }
        return out
    }

    private fun folderCandidates(src: BackupSource): List<Candidate> {
        val out = ArrayList<Candidate>()
        val root = DocumentFile.fromTreeUri(ctx, Uri.parse(src.treeUri ?: return out)) ?: return out
        fun walk(d: DocumentFile, depth: Int) {
            if (depth > 6) return
            for (f in d.listFiles()) {
                if (f.isDirectory) walk(f, depth + 1)
                else if (f.lastModified() / 1000 > src.onlyNewFrom) out += Candidate(f.uri, "doc:${f.uri}", f.name ?: "file", f.length(), f.type, f.lastModified())
            }
        }
        walk(root, 0)
        return out
    }

    private suspend fun scan(src: BackupSource): Int {
        val rules = app.db.rules().enabled()
        val settings = app.prefs.get()
        val cands = if (src.type == "FOLDER") folderCandidates(src) else mediaCandidates(src)
        var queued = 0
        val items = ArrayList<TransferItem>()
        val batchId = app.db.transfers().insertBatch(TransferBatch(origin = "backup", label = src.label))
        for (c in cands) {
            if (queued >= BATCH) break
            val up = app.db.uploaded().get(c.key)
            if (up != null && up.size == c.size) continue                    // already uploaded (source ID + size)
            if (app.db.transfers().pendingWithKey(c.key) > 0) continue          // already in the queue
            val info = FileInfo(Names.sanitize(c.name), c.mime, c.modified)
            val route = if (src.destinationId != 0L)
                Route(src.destinationId, Templates.render(src.pathTemplate, info))
            else Router.resolve(rules, info, settings.defaultDestination, false) ?: continue
            items += TransferItem(
                sourceUri = c.uri.toString(), destinationId = route.destinationId,
                targetPath = listOf(route.dir, info.name).filter { it.isNotEmpty() }.joinToString("/"), name = info.name,
                size = c.size, batchId = batchId, conflict = "KEEP_BOTH", needsCharging = src.chargingOnly,
                mobileOk = !src.wifiOnly, sourceKey = c.key, sourceModified = c.modified, mime = c.mime, origin = "backup",
            )
            queued++
        }
        if (items.isNotEmpty()) app.db.transfers().insertAll(items)
        app.db.backups().update(src.copy(lastRun = System.currentTimeMillis(), backedUp = src.backedUp))
        return queued
    }
}
