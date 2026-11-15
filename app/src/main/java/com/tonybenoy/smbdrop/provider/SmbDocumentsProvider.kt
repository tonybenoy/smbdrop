package com.tonybenoy.smbdrop.provider

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.BitmapFactory
import android.graphics.Point
import android.os.*
import android.os.storage.StorageManager
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import com.tonybenoy.smbdrop.App
import com.tonybenoy.smbdrop.R
import com.tonybenoy.smbdrop.data.Destination
import com.tonybenoy.smbdrop.data.TState
import com.tonybenoy.smbdrop.data.TransferItem
import com.tonybenoy.smbdrop.routing.Names
import com.tonybenoy.smbdrop.smb.RemoteEntry
import com.tonybenoy.smbdrop.smb.SmbConn
import com.tonybenoy.smbdrop.transfer.Scheduler
import com.hierynomus.smbj.share.File
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

class SmbDocumentsProvider : DocumentsProvider() {
    private val app get() = context!!.applicationContext as App
    private val handler by lazy { Handler(HandlerThread("smb-proxy").apply { start() }.looper) }

    private data class Cached(val at: Long, val entries: List<RemoteEntry>)
    private val listings = HashMap<String, Cached>()

    override fun onCreate() = true

    private fun dests(): List<Destination> = runBlocking { app.db.destinations().list() }
    private fun dest(id: Long): Destination = runBlocking { app.db.destinations().get(id) } ?: throw FileNotFoundException()

    private fun parse(docId: String): Pair<Long, String> {
        val i = docId.indexOf(':')
        return docId.substring(0, i).toLong() to docId.substring(i + 1).trim('/')
    }
    private fun docId(d: Long, p: String) = "$d:${p.trim('/')}"
    private fun mimeFor(name: String) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(Names.ext(name)) ?: "application/octet-stream"

    override fun queryRoots(projection: Array<String>?): Cursor {
        val c = MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_DOCUMENT_ID))
        for (d in dests()) {
            c.newRow().add(Root.COLUMN_ROOT_ID, d.id.toString())
                .add(Root.COLUMN_TITLE, "SMB Drop – ${d.nickname}")
                .add(Root.COLUMN_SUMMARY, "${d.host}/${d.share}")
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_SEARCH.and(0))
                .add(Root.COLUMN_ICON, R.drawable.ic_launcher)
                .add(Root.COLUMN_DOCUMENT_ID, docId(d.id, ""))
        }
        return c
    }

    private fun entries(d: Destination, path: String, force: Boolean = false): List<RemoteEntry> {
        val key = "${d.id}:$path"
        synchronized(listings) { listings[key]?.let { if (!force && System.currentTimeMillis() - it.at < 30_000) return it.entries } }
        val e = app.pool.use(d) { it.list(path) }
        synchronized(listings) { listings[key] = Cached(System.currentTimeMillis(), e) }
        return e
    }
    private fun invalidate(id: Long) = synchronized(listings) { listings.keys.removeAll { it.startsWith("$id:") } }

    private fun addRow(c: MatrixCursor, d: Destination, e: RemoteEntry?, path: String) {
        val isDir = e?.isDir ?: true
        val name = e?.name ?: d.nickname
        var flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        if (isDir) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        else flags = flags or Document.FLAG_SUPPORTS_WRITE
        if (!isDir && Thumbs.kind(name) != null) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, docId(d.id, path)).add(Document.COLUMN_DISPLAY_NAME, name)
            .add(Document.COLUMN_MIME_TYPE, if (isDir) Document.MIME_TYPE_DIR else mimeFor(name))
            .add(Document.COLUMN_SIZE, e?.size ?: 0).add(Document.COLUMN_LAST_MODIFIED, e?.modified ?: 0)
            .add(Document.COLUMN_FLAGS, flags)
    }

    private val docCols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val c = MatrixCursor(projection ?: docCols)
        val (id, path) = parse(documentId)
        val d = dest(id)
        if (path.isEmpty()) { addRow(c, d, null, ""); return c }
        val parent = path.substringBeforeLast('/', "")
        val e = entries(d, parent).firstOrNull { it.path == path } ?: throw FileNotFoundException(path)
        addRow(c, d, e, path)
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor {
        val c = object : MatrixCursor(projection ?: docCols) {
            override fun getExtras(): Bundle = Bundle().apply { putBoolean(DocumentsContract_EXTRA_LOADING, false) }
        }
        val (id, path) = parse(parentDocumentId)
        val d = dest(id)
        try {
            for (e in entries(d, path)) addRow(c, d, e, e.path)
        } catch (e: Exception) {
            c.setExtras(Bundle().apply { putString("error", e.message) })
        }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val (a, pa) = parse(parentDocumentId); val (b, pb) = parse(documentId)
        return a == b && (pa.isEmpty() || pb == pa || pb.startsWith("$pa/"))
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val (id, path) = parse(documentId)
        val d = dest(id)
        val sm = context!!.getSystemService(StorageManager::class.java)
        val pfdMode = ParcelFileDescriptor.parseMode(mode)
        if (mode.contains('w')) return openForWrite(d, path, mode)
        val conn = app.pool.get(d)
        val file: File = conn.openRead(path)
        val size = file.fileInformation.standardInformation.endOfFile
        return sm.openProxyFileDescriptor(pfdMode, ReadAheadCallback(file, size), handler)
    }

    /** Read callback with a 4 MB read-ahead window so other apps can seek in large files. */
    internal class ReadAheadCallback(val f: File, val size: Long) : ProxyFileDescriptorCallback() {
        private val win = 4 shl 20
        private var winStart = -1L
        private var winBuf = ByteArray(0)
        override fun onGetSize() = size
        override fun onRead(offset: Long, sz: Int, data: ByteArray): Int {
            try {
                if (offset >= size) return 0
                if (winStart < 0 || offset < winStart || offset + sz > winStart + winBuf.size) {
                    val want = maxOf(win.toLong(), sz.toLong()).coerceAtMost(size - offset).toInt()
                    val b = ByteArray(want); var got = 0
                    while (got < want) { val n = f.read(b, offset + got, got, minOf(1 shl 20, want - got)); if (n <= 0) break; got += n }
                    winBuf = b.copyOf(got); winStart = offset
                }
                val from = (offset - winStart).toInt()
                val n = minOf(sz, winBuf.size - from).coerceAtLeast(0)
                System.arraycopy(winBuf, from, data, 0, n)
                return n
            } catch (e: Exception) { throw ErrnoException("read", OsConstants.EIO) }
        }
        override fun onRelease() { runCatching { f.close() } }
    }

    /** Writes go to a temp file; on close it is uploaded through the transfer engine (REPLACE). */
    private fun openForWrite(d: Destination, path: String, mode: String): ParcelFileDescriptor {
        val tmp = java.io.File(context!!.cacheDir, "up-${System.nanoTime()}-${path.substringAfterLast('/')}")
        if (mode.contains('a') || !mode.contains('t')) {
            // preserve existing content for read-write opens
            runCatching { app.pool.get(d).openRead(path).use { f -> tmp.outputStream().use { o -> f.inputStream.copyTo(o) } } }
        }
        return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.parseMode(mode) or ParcelFileDescriptor.MODE_CREATE, handler) { err ->
            if (err == null) runBlocking {
                app.db.transfers().insert(TransferItem(
                    sourceUri = android.net.Uri.fromFile(tmp).toString(), destinationId = d.id, targetPath = path,
                    name = path.substringAfterLast('/'), size = tmp.length(), conflict = "REPLACE", mobileOk = true,
                    origin = "provider"))
                invalidate(d.id)
                Scheduler.kick(context!!)
            }
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val (id, path) = parse(parentDocumentId)
        val d = dest(id)
        val name = Names.sanitize(displayName)
        val full = if (path.isEmpty()) name else "$path/$name"
        app.pool.use(d) { c ->
            if (mimeType == Document.MIME_TYPE_DIR) c.mkdirs(full)
            else c.openWrite(full, truncate = false).close()
        }
        invalidate(id)
        return docId(id, full)
    }

    override fun deleteDocument(documentId: String) {
        val (id, path) = parse(documentId)
        val d = dest(id)
        val parent = path.substringBeforeLast('/', "")
        val isDir = entries(d, parent, true).firstOrNull { it.path == path }?.isDir ?: false
        app.pool.use(d) { it.delete(path, isDir) }
        invalidate(id)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val (id, path) = parse(documentId)
        val d = dest(id)
        val parent = path.substringBeforeLast('/', "")
        val isDir = entries(d, parent, true).firstOrNull { it.path == path }?.isDir ?: false
        val np = (if (parent.isEmpty()) "" else "$parent/") + Names.sanitize(displayName)
        app.pool.use(d) { it.rename(path, np, isDir) }
        invalidate(id)
        return docId(id, np)
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal?): AssetFileDescriptor {
        val (id, path) = parse(documentId)
        val f = Thumbs.get(context!!, app, dest(id), path, maxOf(sizeHint.x, 96)) ?: throw FileNotFoundException()
        return AssetFileDescriptor(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY), 0, f.length())
    }

    companion object { const val DocumentsContract_EXTRA_LOADING = "android.content.extra.LOADING" }
}

/** On-disk LRU thumbnail cache (200 MB cap) for photos, videos and PDFs; shared by the provider and the in-app browser. */
object Thumbs {
    private const val CAP = 200L shl 20
    private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
    private val videoExt = setOf("mp4", "m4v", "mov", "mkv", "webm", "3gp", "avi")

    /** "image", "video", "pdf" or null when no thumbnail can be made for this file name. */
    fun kind(name: String): String? = when (Names.ext(name)) {
        in imageExt -> "image"; in videoExt -> "video"; "pdf" -> "pdf"; else -> null
    }

    private val handler by lazy { Handler(HandlerThread("smb-thumbs").apply { start() }.looper) }

    fun get(c: android.content.Context, app: App, d: Destination, path: String, px: Int): java.io.File? {
        val kind = kind(path.substringAfterLast('/')) ?: return null
        val dir = java.io.File(c.cacheDir, "thumbs").apply { mkdirs() }
        val f = java.io.File(dir, "${d.id}_${path.hashCode()}_$px.jpg")
        if (f.exists()) { f.setLastModified(System.currentTimeMillis()); return f }
        return try {
            val bmp = app.pool.use(d) { conn ->
                conn.openRead(path).use { file ->
                    val size = file.fileInformation.standardInformation.endOfFile
                    when (kind) {
                        "image" -> if (size > 24L shl 20) null else image(file.inputStream.readBytes(), px)
                        "video" -> video(file, size, px)
                        else -> pdf(c, file, size, px)
                    }
                }
            } ?: return null
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
            trim(dir); f
        } catch (e: Throwable) { null }
    }

    private fun image(bytes: ByteArray, px: Int): android.graphics.Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1; while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /** A frame about one second in, read straight from the share with random access (no full download). */
    private fun video(file: File, size: Long, px: Int): android.graphics.Bitmap? {
        val src = object : android.media.MediaDataSource() {
            private var winStart = -1L; private var win = ByteArray(0)
            override fun getSize() = size
            override fun close() {}
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, n: Int): Int {
                if (position >= size) return -1
                val want = minOf(n.toLong(), size - position).toInt()
                if (winStart < 0 || position < winStart || position + want > winStart + win.size) {
                    val len = minOf(1L shl 20, size - position).toInt()
                    val b = ByteArray(len); var got = 0
                    while (got < len) { val r = file.read(b, position + got, got, len - got); if (r <= 0) break; got += r }
                    if (got <= 0) return -1
                    win = b.copyOf(got); winStart = position
                }
                val from = (position - winStart).toInt()
                val k = minOf(want, win.size - from).coerceAtLeast(0)
                System.arraycopy(win, from, buffer, offset, k)
                return if (k == 0) -1 else k
            }
        }
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(src)
            val frame = r.getFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.getFrameAtTime(0) ?: return null
            val scale = px.toFloat() / maxOf(frame.width, frame.height)
            if (scale >= 1f) frame else android.graphics.Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true)
        } finally { runCatching { r.release() } }
    }

    /** First page rendered on white; the file is read through a seekable proxy descriptor, not downloaded. */
    private fun pdf(c: android.content.Context, file: File, size: Long, px: Int): android.graphics.Bitmap? {
        val sm = c.getSystemService(StorageManager::class.java)
        val pfd = sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, SmbDocumentsProvider.ReadAheadCallback(file, size), handler)
        try {
            android.graphics.pdf.PdfRenderer(pfd).use { r ->
                if (r.pageCount == 0) return null
                r.openPage(0).use { page ->
                    val w = px; val h = (px * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
        } finally { runCatching { pfd.close() } }
    }

    private fun trim(dir: java.io.File) {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= CAP) return
        for (f in files.sortedBy { it.lastModified() }) { total -= f.length(); f.delete(); if (total <= CAP * 0.8) break }
    }
}
