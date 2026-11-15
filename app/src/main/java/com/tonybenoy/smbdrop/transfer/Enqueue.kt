package com.tonybenoy.smbdrop.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tonybenoy.smbdrop.App
import com.tonybenoy.smbdrop.data.TState
import com.tonybenoy.smbdrop.data.TransferBatch
import com.tonybenoy.smbdrop.data.TransferItem
import com.tonybenoy.smbdrop.routing.FileInfo
import com.tonybenoy.smbdrop.routing.Names
import com.tonybenoy.smbdrop.routing.Route

data class SourceFile(val uri: Uri, val name: String, val size: Long, val mime: String?, val modified: Long)

object Enqueue {
    private fun hasMediaPermission(c: Context): Boolean {
        val perms = if (android.os.Build.VERSION.SDK_INT >= 33)
            listOf(android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_IMAGES)
        else listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        return perms.all { androidx.core.content.ContextCompat.checkSelfPermission(c, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    }

    /**
     * Shared URIs only carry a temporary grant that dies with the share screen. Keep access by
     * (1) a persistable grant, (2) direct MediaStore access with the media permission, or
     * (3) copying into app storage. Copies are deleted after upload.
     */
    suspend fun stage(c: Context, f: SourceFile, onProgress: (Long) -> Unit = {}): SourceFile =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (f.uri.scheme == "file") return@withContext f
            if (f.uri.authority == "media" && hasMediaPermission(c)) return@withContext f
            val persisted = runCatching {
                c.contentResolver.takePersistableUriPermission(f.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); true
            }.getOrDefault(false)
            if (persisted) return@withContext f
            val dir = java.io.File(c.filesDir, "staging").apply { mkdirs() }
            val out = java.io.File(dir, "${System.nanoTime()}-${f.name}")
            var copied = 0L
            c.contentResolver.openInputStream(f.uri)?.use { i ->
                out.outputStream().use { o ->
                    val buf = ByteArray(1 shl 20)
                    while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); copied += n; onProgress(copied) }
                }
            } ?: throw java.io.FileNotFoundException(f.name)
            f.copy(uri = Uri.fromFile(out), size = out.length())
        }

    fun describe(c: Context, uri: Uri, mimeHint: String? = null): SourceFile {
        var name: String? = null; var size = -1L
        runCatching {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) { name = it.getString(0); size = if (it.isNull(1)) -1 else it.getLong(1) }
            }
        }
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        if (size < 0 && uri.scheme == "file") size = runCatching { java.io.File(uri.path!!).length() }.getOrDefault(-1L)
        val mime = c.contentResolver.getType(uri) ?: mimeHint
        return SourceFile(uri, Names.sanitize(name!!), size, mime, System.currentTimeMillis())
    }

    /** Adds files to the queue as one batch. `paused` = "add to queue for later". */
    suspend fun upload(
        c: Context, files: List<SourceFile>, destinationId: Long, dir: String, label: String, origin: String,
        conflict: String = "KEEP_BOTH", mobileOk: Boolean = false, paused: Boolean = false,
    ): Int {
        val app = c.applicationContext as App
        val dao = app.db.transfers()
        val batch = dao.insertBatch(TransferBatch(origin = origin, label = label))
        val items = files.map {
            TransferItem(
                sourceUri = it.uri.toString(), destinationId = destinationId,
                targetPath = listOf(dir.trim('/'), it.name).filter { s -> s.isNotEmpty() }.joinToString("/"),
                name = it.name, size = it.size, batchId = batch, conflict = conflict, mobileOk = mobileOk,
                state = if (paused) TState.PAUSED else TState.QUEUED, mime = it.mime,
                sourceKey = "uri:${it.uri}", sourceModified = it.modified, origin = origin,
            )
        }
        dao.insertAll(items)
        if (!paused) Scheduler.kick(app)
        return items.size
    }

    /** Queue a server-to-server copy of files/folders; folders are expanded recursively here. */
    suspend fun copy(c: Context, src: com.tonybenoy.smbdrop.data.Destination, entries: List<com.tonybenoy.smbdrop.smb.RemoteEntry>, dstId: Long, dstDir: String, label: String): Int {
        val app = c.applicationContext as App
        val items = ArrayList<TransferItem>()
        val batch = app.db.transfers().insertBatch(TransferBatch(origin = "copy", label = label))
        suspend fun walk(e: com.tonybenoy.smbdrop.smb.RemoteEntry, rel: String) {
            if (e.isDir) {
                val kids = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.pool.use(src) { it.list(e.path) } }
                kids.forEach { walk(it, "$rel/${it.name}") }
            } else items += TransferItem(direction = "copy", sourceUri = "${src.id}|${e.path}", destinationId = dstId,
                targetPath = listOf(dstDir.trim('/'), rel.trim('/')).filter { it.isNotEmpty() }.joinToString("/"),
                name = e.name, size = e.size, batchId = batch, origin = "copy", mobileOk = false)
        }
        entries.forEach { walk(it, it.name) }
        app.db.transfers().insertAll(items)
        Scheduler.kick(app)
        return items.size
    }

    suspend fun download(c: Context, destinationId: Long, remotePath: String, name: String, size: Long): Long {
        val app = c.applicationContext as App
        val id = app.db.transfers().insert(
            TransferItem(direction = "down", sourceUri = remotePath, destinationId = destinationId, targetPath = name,
                name = name, size = size, origin = "browse", mobileOk = false)
        )
        Scheduler.kick(app)
        return id
    }
}
