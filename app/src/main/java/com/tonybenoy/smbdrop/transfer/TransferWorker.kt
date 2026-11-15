package com.tonybenoy.smbdrop.transfer

import com.tonybenoy.smbdrop.ui.tr
import com.tonybenoy.smbdrop.ui.trf

import android.app.Notification
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.tonybenoy.smbdrop.App
import com.tonybenoy.smbdrop.R
import com.tonybenoy.smbdrop.data.*
import com.tonybenoy.smbdrop.routing.Names
import com.tonybenoy.smbdrop.smb.SmbConn
import com.tonybenoy.smbdrop.smb.SmbError
import com.tonybenoy.smbdrop.smb.toSmbError
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.security.MessageDigest

class TransferWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val app = ctx.applicationContext as App
    private val dao get() = app.db.transfers()
    private val claim = Mutex()
    private var done = 0
    private var failed = 0

    override suspend fun getForegroundInfo() = info(tr("Preparing transfers…"), 0, 0)

    private fun info(text: String, p: Int, max: Int): ForegroundInfo {
        val n: Notification = NotificationCompat.Builder(ctx, App.CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("SMB Drop")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(max, p, max == 0).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(1, n)
    }

    override suspend fun doWork(): Result {
        val s0 = app.prefs.get()
        try { setForeground(info(tr("Starting…"), 0, 0)) } catch (_: Exception) {}
        while (true) {
            done = 0; failed = 0
            val s = app.prefs.get()
            coroutineScope {
                repeat(s.concurrency.coerceIn(1, 4)) { launch(Dispatchers.IO) { loop() } }
            }
            if (dao.runnableCount() == 0 || !hasRunnable()) break
        }
        if (done + failed > 0 && s0.notifyDone) summary()
        return Result.success()
    }

    private suspend fun hasRunnable(): Boolean {
        val s = app.prefs.get()
        return dao.queued().any { Conditions.blocker(ctx, s, it.mobileOk, it.needsCharging, it.origin == "backup") == null }
    }

    private suspend fun nextItem(): TransferItem? = claim.withLock {
        val s = app.prefs.get()
        val it = dao.queued().firstOrNull {
            Conditions.blocker(ctx, s, it.mobileOk, it.needsCharging, it.origin == "backup") == null
        } ?: return null
        dao.setState(it.id, TState.ACTIVE)
        it.copy(state = TState.ACTIVE)
    }

    private suspend fun loop() {
        while (true) {
            if (isStopped) return
            val item = nextItem() ?: return
            try {
                setForeground(info("${item.name}", 0, 0))
                when (item.direction) { "up" -> upload(item); "down" -> download(item); else -> copy(item) }
            } catch (e: CancellationException) {
                dao.setState(item.id, TState.QUEUED); throw e
            } catch (e: Exception) {
                handleError(item, e)
            }
        }
    }

    private suspend fun handleError(item: TransferItem, e: Exception) {
        if (e is PausedException) return
        val err = e.toSmbError()
        val cur = dao.get(item.id) ?: return
        if (cur.state == TState.PAUSED) return
        val attempts = cur.attempts + 1
        if (err.retryable && attempts < 4) {
            dao.update(cur.copy(attempts = attempts, state = TState.WAITING, error = err.userMessage))
            app.pool.drop(item.destinationId)
            // back-off: 30 s, 2 min, 10 min, then wait for a network change
            val wait = listOf(30_000L, 120_000L, 600_000L)[attempts - 1]
            app.scope.launch { delay(wait); dao.wakeWaitingOne(item.id); Scheduler.kick(ctx) }
        } else {
            failed++
            dao.update(cur.copy(attempts = attempts, state = if (err.retryable) TState.WAITING else TState.FAILED, error = err.userMessage))
            if (app.prefs.get().notifyErrors && !err.retryable) notifyError(item, err)
        }
    }

    private class PausedException : Exception()

    private suspend fun checkPaused(id: Long) {
        val st = dao.stateOf(id)
        if (st != TState.ACTIVE) throw PausedException()
    }

    private fun openSource(uri: Uri, offset: Long): Pair<java.io.InputStream, Long> {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
        if (pfd != null) {
            val fis = FileInputStream(pfd.fileDescriptor)
            return try {
                fis.channel.position(offset)
                object : java.io.FilterInputStream(fis) { override fun close() { super.close(); pfd.close() } } to pfd.statSize
            } catch (e: Exception) {
                fis.close(); pfd.close(); fallbackStream(uri, offset)
            }
        }
        return fallbackStream(uri, offset)
    }

    private fun fallbackStream(uri: Uri, offset: Long): Pair<java.io.InputStream, Long> {
        val i = ctx.contentResolver.openInputStream(uri) ?: throw SmbError.Gone()
        var left = offset
        while (left > 0) { val n = i.skip(left); if (n <= 0) break; left -= n }
        return i to -1L
    }

    private suspend fun upload(item0: TransferItem) {
        var item = item0
        val dest = dao.let { app.db.destinations().get(item.destinationId) } ?: throw SmbError.Gone()
        val uri = Uri.parse(item.sourceUri)
        val settings = app.prefs.get()
        val conn = app.pool.get(dest)
        val dir = item.targetPath.substringBeforeLast('/', "")
        val name = item.targetPath.substringAfterLast('/')
        val partPath = (if (dir.isEmpty()) "" else "$dir/") + name + ".smbdrop-part"

        // Resolve the conflict only on the first attempt of an item.
        var finalName = name
        if (item.bytesDone == 0L) {
            val exists = withContext(Dispatchers.IO) { conn.mkdirs(dir); conn.fileExists(item.targetPath) }
            if (exists) when (item.conflict) {
                "SKIP" -> { dao.setState(item.id, TState.DONE); done++; return }
                "KEEP_BOTH" -> {
                    var n = 2
                    while (conn.fileExists((if (dir.isEmpty()) "" else "$dir/") + Names.numbered(name, n))) n++
                    finalName = Names.numbered(name, n)
                }
            }
        } else finalName = item.targetPath.substringAfterLast('/')
        val finalPath = (if (dir.isEmpty()) "" else "$dir/") + finalName

        // The real part-file size on the server wins over our saved counter.
        val remotePart = if (conn.fileExists(partPath)) conn.size(partPath) else 0L
        val (input, srcSize) = try { openSource(uri, remotePart) } catch (e: Exception) { throw SmbError.Protocol("Couldn't read the shared file") }
        val total = if (item.size > 0) item.size else srcSize
        var offset = remotePart
        if (total in 1 until offset) { offset = 0; input.close(); return restartFromZero(item) }

        input.use { inp ->
            val file = conn.openWrite(partPath)
            file.use { f ->
                val buf = ByteArray(1 shl 20)
                var lastSave = offset
                var lastTick = System.currentTimeMillis(); var tickBytes = offset
                var speed = 0L
                var counter = 0
                while (true) {
                    val n = withContext(Dispatchers.IO) { inp.read(buf) }
                    if (n < 0) break
                    f.write(buf, offset, 0, n)
                    offset += n
                    if (++counter % 4 == 0) checkPaused(item.id)
                    val now = System.currentTimeMillis()
                    if (now - lastTick >= 1000) {
                        speed = (offset - tickBytes) * 1000 / (now - lastTick)
                        lastTick = now; tickBytes = offset
                        dao.progress(item.id, offset, speed)
                        if (settings.notifyProgress) setForeground(info("${item.name} · ${fmt(speed)}/s", ((offset * 100) / maxOf(total, 1)).toInt(), 100))
                    }
                    if (offset - lastSave >= 4L shl 20) { dao.progress(item.id, offset, speed); lastSave = offset }
                }
                f.flush()
                dao.progress(item.id, offset, 0)
                val remoteSize = f.fileInformation.standardInformation.endOfFile
                if (remoteSize != offset) throw SmbError.Protocol("Size mismatch after upload")
                if (item.conflict == "REPLACE" && conn.fileExists(finalPath)) conn.delete(finalPath, false)
                conn.renameOpen(f, finalPath, item.conflict == "REPLACE")
            }
        }
        if (settings.verifyHash && offset < 200L shl 20) verifyHash(conn, uri, finalPath)

        dao.update((dao.get(item.id) ?: item).copy(state = TState.DONE, bytesDone = offset, size = offset, error = null,
            targetPath = finalPath, speed = 0))
        item.sourceKey?.let {
            app.db.uploaded().put(UploadedFile(it, offset, item.sourceModified, finalPath, dest.id, sourceUri = item.sourceUri))
        }
        if (item.uriIsTemp()) runCatching { java.io.File(uri.path!!).delete() }
        done++
    }

    private fun TransferItem.uriIsTemp() = sourceUri.startsWith("file:") && (sourceUri.contains("/cache/") || sourceUri.contains("/staging/"))

    private suspend fun restartFromZero(item: TransferItem) {
        val dest = app.db.destinations().get(item.destinationId) ?: return
        val part = item.targetPath + ".smbdrop-part"
        runCatching { app.pool.get(dest).delete(part, false) }
        dao.update(item.copy(bytesDone = 0, state = TState.QUEUED))
    }

    private fun verifyHash(conn: SmbConn, uri: Uri, remote: String) {
        val a = MessageDigest.getInstance("SHA-256"); val b = MessageDigest.getInstance("SHA-256")
        ctx.contentResolver.openInputStream(uri)?.use { s -> val buf = ByteArray(1 shl 18); while (true) { val n = s.read(buf); if (n < 0) break; a.update(buf, 0, n) } }
        conn.openRead(remote).use { f -> f.inputStream.use { s -> val buf = ByteArray(1 shl 18); while (true) { val n = s.read(buf); if (n < 0) break; b.update(buf, 0, n) } } }
        if (!a.digest().contentEquals(b.digest())) throw SmbError.Protocol("Checksum mismatch after upload")
    }

    private suspend fun download(item: TransferItem) {
        val dest = app.db.destinations().get(item.destinationId) ?: throw SmbError.Gone()
        val conn = app.pool.get(dest)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(Names.ext(item.name)) ?: "application/octet-stream"
        val out: java.io.OutputStream
        var finish: () -> Unit = {}
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, item.name); put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw SmbError.Permission()
            out = ctx.contentResolver.openOutputStream(u)!!
            finish = { ctx.contentResolver.update(u, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) }
        } else {
            val d = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).also { it.mkdirs() }
            out = java.io.File(d, item.name).outputStream()
        }
        var offset = 0L
        out.use { o ->
            conn.openRead(item.sourceUri).use { f ->
                val total = f.fileInformation.standardInformation.endOfFile
                val buf = ByteArray(1 shl 20); var tick = System.currentTimeMillis(); var tb = 0L; var c = 0
                while (true) {
                    val n = f.read(buf, offset, 0, buf.size)
                    if (n <= 0) break
                    o.write(buf, 0, n); offset += n
                    if (++c % 4 == 0) checkPaused(item.id)
                    val now = System.currentTimeMillis()
                    if (now - tick >= 1000) { dao.progress(item.id, offset, (offset - tb) * 1000 / (now - tick)); tick = now; tb = offset }
                }
                if (offset != total) throw SmbError.Network("Download incomplete")
            }
        }
        finish()
        dao.update((dao.get(item.id) ?: item).copy(state = TState.DONE, bytesDone = offset, size = offset, speed = 0))
        done++
    }

    /** Server-to-server copy: stream from the source share straight into the target share (resumable via the part file). */
    private suspend fun copy(item: TransferItem) {
        val (sid, spath) = item.sourceUri.split('|', limit = 2).let { it[0].toLong() to it[1] }
        val src = app.db.destinations().get(sid) ?: throw SmbError.Gone()
        val dst = app.db.destinations().get(item.destinationId) ?: throw SmbError.Gone()
        val sc = app.pool.get(src); val dc = app.pool.get(dst)
        val dir = item.targetPath.substringBeforeLast('/', "")
        val name = item.targetPath.substringAfterLast('/')
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        var finalName = name
        withContext(Dispatchers.IO) { dc.mkdirs(dir) }
        if (item.bytesDone == 0L && dc.fileExists(item.targetPath)) when (item.conflict) {
            "SKIP" -> { dao.setState(item.id, TState.DONE); done++; return }
            "KEEP_BOTH" -> { var n = 2; while (dc.fileExists(prefix + Names.numbered(name, n))) n++; finalName = Names.numbered(name, n) }
        }
        val finalPath = prefix + finalName
        val partPath = "$prefix$finalName.smbdrop-part"
        var offset = if (dc.fileExists(partPath)) dc.size(partPath) else 0L
        val input = sc.openRead(spath)
        input.use { sf ->
            val total = sf.fileInformation.standardInformation.endOfFile
            if (offset > total) offset = 0
            dc.openWrite(partPath).use { out ->
                val buf = ByteArray(1 shl 20); var tick = System.currentTimeMillis(); var tb = offset; var c = 0; var speed = 0L
                while (true) {
                    val n = withContext(Dispatchers.IO) { sf.read(buf, offset, 0, buf.size) }
                    if (n <= 0) break
                    out.write(buf, offset, 0, n); offset += n
                    if (++c % 4 == 0) checkPaused(item.id)
                    val now = System.currentTimeMillis()
                    if (now - tick >= 1000) { speed = (offset - tb) * 1000 / (now - tick); tick = now; tb = offset; dao.progress(item.id, offset, speed)
                        setForeground(info("${item.name} · ${fmt(speed)}/s", ((offset * 100) / maxOf(total, 1)).toInt(), 100)) }
                }
                out.flush()
                if (out.fileInformation.standardInformation.endOfFile != total || offset != total) throw SmbError.Protocol("Size mismatch after upload")
                if (item.conflict == "REPLACE" && dc.fileExists(finalPath)) dc.delete(finalPath, false)
                dc.renameOpen(out, finalPath, item.conflict == "REPLACE")
            }
        }
        dao.update((dao.get(item.id) ?: item).copy(state = TState.DONE, bytesDone = offset, size = offset, error = null, targetPath = finalPath, speed = 0))
        done++
    }

    private fun summary() {
        val n = NotificationCompat.Builder(ctx, App.CH_DONE).setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(tr("Transfers finished")).setContentText(trf("%1\$d done", done) + if (failed > 0) ", " + trf("%1\$d failed", failed) else "")
            .setAutoCancel(true).build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).notify(2, n) }
    }

    private fun notifyError(item: TransferItem, e: SmbError) {
        val n = NotificationCompat.Builder(ctx, App.CH_ERROR).setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(trf("Couldn't send %1\$s", item.name)).setContentText(tr(e.userMessage) + (e.fix?.let { " · " + tr(it) } ?: ""))
            .setAutoCancel(true).build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).notify(1000 + item.id.toInt(), n) }
    }

    companion object {
        fun fmt(b: Long): String = when {
            b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
            b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
            b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
            else -> "$b B"
        }
    }
}
