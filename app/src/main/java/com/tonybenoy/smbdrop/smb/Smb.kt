package com.tonybenoy.smbdrop.smb

import com.tonybenoy.smbdrop.data.Crypto
import com.tonybenoy.smbdrop.data.Destination
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.hierynomus.protocol.transport.TransportException
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** Errors mapped to app-level categories to decide retry vs fail. */
sealed class SmbError(val userMessage: String, val retryable: Boolean, val fix: String? = null) : Exception(userMessage) {
    class Network(m: String = "Can't reach the server") : SmbError(m, true, "Check Wi-Fi and that the server is on")
    class Auth : SmbError("Wrong username or password", false, "Edit login")
    class Permission : SmbError("Permission denied on this share or folder", false, "Choose another folder")
    class DiskFull : SmbError("The server's disk is full", false, "Choose another folder")
    class Gone : SmbError("File no longer exists", false)
    class NoShare : SmbError("That share was not found on this server", false, "Check the share name")
    class Protocol(m: String) : SmbError(m, false, "Export log")
    class Smb1Only : SmbError("This server only supports SMB1, which isn't supported. Enable SMB2/3 on the server.", false, "How to enable SMB2")
}

fun Throwable.toSmbError(): SmbError {
    if (this is SmbError) return this
    if (this is SecurityException) return SmbError.Protocol("Lost access to this file. Remove it and share it again.")
    if (this is SMBApiException) {
        return when (statusCode) {
            0xC000006DL, 0xC000006AL, 0xC000006EL, 0xC0000071L, 0xC0000072L, 0xC0000234L -> SmbError.Auth()
            0xC0000022L, 0xC0000043L -> SmbError.Permission()
            0xC000007FL -> SmbError.DiskFull()
            0xC0000034L, 0xC000003AL, 0xC0000033L -> SmbError.Gone()
            0xC00000CCL -> SmbError.NoShare()
            else -> SmbError.Protocol("${statusCode.toString(16)}: ${message ?: "SMB error"}")
        }
    }
    var t: Throwable? = this
    while (t != null) {
        if (t is java.net.UnknownHostException) return SmbError.Network("Can't find that server")
        if (t is IOException || t is TransportException || t is java.util.concurrent.TimeoutException) return SmbError.Network()
        t = t.cause
    }
    val m = message ?: javaClass.simpleName
    if (m.contains("SMB1", true) || m.contains("negotiat", true)) return SmbError.Smb1Only()
    if (m.contains("logon", true) || m.contains("auth", true)) return SmbError.Auth()
    return SmbError.Protocol(m)
}

data class RemoteEntry(
    val name: String, val path: String, val isDir: Boolean, val size: Long, val modified: Long,
)

private const val DIR = 0x10L
const val TRASH = ".smbdrop-trash"

/** One live SMB connection to a destination's share. All calls are blocking; use on Dispatchers.IO. */
class SmbConn(val dest: Destination) : AutoCloseable {
    private val client: SMBClient
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    @Volatile var lastUsed = System.currentTimeMillis()

    init {
        installBouncyCastle()
        val cfg = SmbConfig.builder()
            .withDialects(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .withMultiProtocolNegotiate(true)
            .withSigningRequired(false)
            .build()
        client = SMBClient(cfg)
    }

    private fun auth(): AuthenticationContext = when {
        dest.guest -> AuthenticationContext.guest()
        dest.username.isBlank() -> AuthenticationContext.anonymous()
        else -> AuthenticationContext(dest.username, Crypto.decrypt(dest.secret).toCharArray(), dest.domain)
    }

    @Synchronized fun connectSession(): Session {
        session?.let { s -> if (connection?.isConnected == true) return s }
        close()
        try {
            val h = dest.host.trim()
            val c = if (h.count { it == ':' } == 1 && h.substringAfter(':').all(Char::isDigit))
                client.connect(h.substringBefore(':'), h.substringAfter(':').toInt()) else client.connect(h)
            connection = c
            session = c.authenticate(auth())
            return session!!
        } catch (e: Exception) { close(); throw e.toSmbError() }
    }

    @Synchronized fun disk(): DiskShare {
        share?.let { if (it.isConnected) return it }
        val s = connectSession()
        try {
            share = s.connectShare(dest.share) as DiskShare
            return share!!
        } catch (e: Exception) { throw e.toSmbError() }
    }

    fun shareNames(): List<String> = try {
        val s = connectSession()
        val tr = SMBTransportFactories.SRVSVC.getTransport(s)
        ServerService(tr).shares0.map { it.netName }.filter { !it.endsWith("$") }
    } catch (e: Exception) { throw e.toSmbError() }

    private fun full(p: String): String =
        listOf(dest.basePath, p).flatMap { it.split('/', '\\') }.filter { it.isNotEmpty() }.joinToString("\\")

    fun absolute(p: String) = full(p)

    fun list(path: String): List<RemoteEntry> = try {
        disk().list(full(path)).filter { it.fileName != "." && it.fileName != ".." && it.fileName != TRASH }.map {
            val dir = (it.fileAttributes and DIR) != 0L
            RemoteEntry(it.fileName, listOf(path.trim('/'), it.fileName).filter { s -> s.isNotEmpty() }.joinToString("/"),
                dir, it.endOfFile, it.changeTime.toEpochMillis())
        }
    } catch (e: Exception) { throw e.toSmbError() }

    fun exists(path: String): Boolean = try { disk().let { it.fileExists(full(path)) || it.folderExists(full(path)) } } catch (e: Exception) { throw e.toSmbError() }
    fun fileExists(path: String): Boolean = try { disk().fileExists(full(path)) } catch (e: Exception) { throw e.toSmbError() }
    fun size(path: String): Long = try { disk().getFileInformation(full(path)).standardInformation.endOfFile } catch (e: Exception) { throw e.toSmbError() }

    fun mkdirs(path: String) {
        try {
            val d = disk()
            var cur = ""
            for (seg in full(path).split('\\').filter { it.isNotEmpty() }) {
                cur = if (cur.isEmpty()) seg else "$cur\\$seg"
                if (!d.folderExists(cur)) d.mkdir(cur)
            }
        } catch (e: Exception) { throw e.toSmbError() }
    }

    fun delete(path: String, isDir: Boolean) {
        try { if (isDir) disk().rmdir(full(path), true) else disk().rm(full(path)) } catch (e: Exception) { throw e.toSmbError() }
    }

    fun rename(path: String, newPath: String, isDir: Boolean) {
        try {
            val d = disk()
            val acc = EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ)
            val opts = if (isDir) EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE) else EnumSet.noneOf(SMB2CreateOptions::class.java)
            val e = d.open(full(path), acc, EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, opts)
            e.use { it.rename(full(newPath), false) }
        } catch (e: Exception) { throw e.toSmbError() }
    }

    fun openRead(path: String): File = try {
        disk().openFile(full(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null)
    } catch (e: Exception) { throw e.toSmbError() }

    /** Opens (creating) a file for writing without truncating, so resume can append. */
    fun openWrite(path: String, truncate: Boolean = false): File = try {
        disk().openFile(full(path), EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE, AccessMask.DELETE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL,
            if (truncate) SMB2CreateDisposition.FILE_OVERWRITE_IF else SMB2CreateDisposition.FILE_OPEN_IF, null)
    } catch (e: Exception) { throw e.toSmbError() }

    fun renameOpen(f: File, newPath: String, replace: Boolean) {
        try { f.rename(full(newPath), replace) } catch (e: Exception) { throw e.toSmbError() }
    }

    fun freeSpace(): Long = try { disk().shareInformation.freeSpace } catch (e: Exception) { -1 }

    @Synchronized override fun close() {
        runCatching { share?.close() }; runCatching { session?.close() }; runCatching { connection?.close() }
        share = null; session = null; connection = null
    }

    companion object {
        @Volatile private var bcInstalled = false
        @Synchronized fun installBouncyCastle() {
            if (bcInstalled) return
            java.security.Security.removeProvider("BC")
            java.security.Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
            bcInstalled = true
        }
    }
}

/** Pool of one connection per destination, reused across transfers. */
class SmbPool {
    private val conns = HashMap<Long, SmbConn>()

    @Synchronized fun get(d: Destination): SmbConn {
        val old = conns[d.id]
        if (old != null && old.dest == d) { old.lastUsed = System.currentTimeMillis(); return old }
        old?.close()
        return SmbConn(d).also { conns[d.id] = it }
    }

    @Synchronized fun drop(id: Long) { conns.remove(id)?.close() }
    @Synchronized fun dropAll() { conns.values.forEach { it.close() }; conns.clear() }

    /** Run with a connection; on a stale-session failure, reconnect once. */
    fun <T> use(d: Destination, block: (SmbConn) -> T): T {
        try { return block(get(d)) } catch (e: Exception) {
            val err = e.toSmbError()
            // A connection dropped under us (network change, idle close) is retried once on a fresh session.
            val closed = e is IllegalStateException || (e.message ?: "").contains("closed", ignoreCase = true)
            if (err is SmbError.Network || closed) {
                drop(d.id)
                return block(get(d))
            }
            throw err
        }
    }
}

fun isDirAttr(attrs: Long) = EnumWithValue.EnumUtils.isSet(attrs, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
