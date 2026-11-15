package com.tonybenoy.smbdrop.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tonybenoy.smbdrop.App
import com.tonybenoy.smbdrop.R
import com.tonybenoy.smbdrop.share.ShareActivity
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.tonybenoy.smbdrop.data.*
import com.tonybenoy.smbdrop.discovery.FoundServer
import com.tonybenoy.smbdrop.smb.SmbConn
import com.tonybenoy.smbdrop.smb.toSmbError
import com.tonybenoy.smbdrop.transfer.Scheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class TestResult(val ok: Boolean, val message: String, val free: Long = -1)

class AppVm(val a: Application) : AndroidViewModel(a) {
    val app = a as App
    val db get() = app.db
    val prefs get() = app.prefs
    private fun <T> Flow<T>.state(init: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), init)

    val settings = prefs.flow.state(Settings())
    val destinations = db.destinations().all().state(emptyList())
    val rules = db.rules().all().state(emptyList())
    val backups = db.backups().all().state(emptyList())
    val open = db.transfers().open().state(emptyList())
    val recentDone = db.transfers().recentDone().state(emptyList())
    val favourites = db.favourites().all().state(emptyList())
    val uploadedToday = db.uploaded().countSince(System.currentTimeMillis() - 24 * 3600_000L).state(0)
    val recentUploads = db.uploaded().recent().state(emptyList())

    // ---- discovery ----
    val found = MutableStateFlow<List<FoundServer>>(emptyList())
    val scanning = MutableStateFlow(false)
    private var scanJob: Job? = null
    fun scan() {
        scanJob?.cancel(); found.value = emptyList(); scanning.value = true
        scanJob = viewModelScope.launch {
            try { app.discovery.scan().collect { s -> found.update { it + s } } } finally { scanning.value = false }
        }
    }

    // ---- reachability dots ----
    val online = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    fun refreshOnline() = viewModelScope.launch(Dispatchers.IO) {
        for (d in db.destinations().list()) launch {
            var ok = app.discovery.reachable(d.host)
            if (!ok) {
                // IP changed? find it again by name
                val ip = app.discovery.resolve(d.host)
                ok = ip != null && app.discovery.reachable(ip)
            }
            online.update { it + (d.id to ok) }
        }
    }

    // ---- servers ----
    fun listShares(host: String, user: String, pass: String, domain: String, guest: Boolean, savedSecret: String? = null, onDone: (Result<List<String>>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching {
                val d = Destination(id = -1, nickname = "", host = host, share = "IPC$", username = user, domain = domain, guest = guest,
                    secret = if (pass.isEmpty() && savedSecret != null) savedSecret else Crypto.encrypt(pass))
                SmbConn(d).use { it.shareNames() }
            }.recoverCatching { throw it.toSmbError() }
            withContext(Dispatchers.Main) { onDone(r) }
        }
    }

    fun test(d: Destination, pass: String?, onDone: (TestResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = try {
                val dd = if (pass != null) d.copy(secret = Crypto.encrypt(pass)) else d
                SmbConn(dd.copy(id = -1)).use { c ->
                    c.list(""); TestResult(true, "Connected", c.freeSpace())
                }
            } catch (e: Exception) { TestResult(false, tr(e.toSmbError().userMessage)) }
            withContext(Dispatchers.Main) { onDone(res) }
        }
    }

    fun saveDestination(d: Destination, pass: String?, onSaved: (Long) -> Unit = {}) = viewModelScope.launch {
        val enc = if (pass != null) Crypto.encrypt(pass) else d.secret
        val dd = d.copy(secret = enc)
        val id = if (d.id == 0L) db.destinations().insert(dd) else { db.destinations().update(dd); app.pool.drop(d.id); d.id }
        if (prefs.get().defaultDestination == 0L) prefs.setDefaultDestination(id)
        publishShortcuts()
        refreshOnline()
        onSaved(id)
    }

    fun deleteDestination(d: Destination) = viewModelScope.launch {
        db.destinations().delete(d.id); db.rules().deleteForDest(d.id); app.pool.drop(d.id)
        if (prefs.get().defaultDestination == d.id) prefs.setDefaultDestination(0)
        publishShortcuts()
    }

    fun publishShortcuts() = viewModelScope.launch(Dispatchers.IO) {
        val list = db.destinations().list()
        ShortcutManagerCompat.removeAllDynamicShortcuts(a)
        for (d in list.take(4)) {
            val sc = ShortcutInfoCompat.Builder(a, "dest_${d.id}")
                .setShortLabel(d.nickname).setLongLabel("SMB Drop – ${d.nickname}")
                .setIcon(IconCompat.createWithResource(a, R.drawable.ic_launcher))
                .setCategories(setOf("com.tonybenoy.smbdrop.category.SHARE_TARGET"))
                .setIntent(Intent(a, ShareActivity::class.java).setAction(Intent.ACTION_SEND))
                .setLongLived(true).build()
            ShortcutManagerCompat.pushDynamicShortcut(a, sc)
        }
    }

    // ---- queue ----
    fun pause(i: TransferItem) = viewModelScope.launch { db.transfers().setState(i.id, TState.PAUSED) }
    fun resume(i: TransferItem) = viewModelScope.launch { db.transfers().requeue(i.id); Scheduler.kick(a) }
    fun cancel(i: TransferItem) = viewModelScope.launch { db.transfers().delete(i.id) }
    fun sendNow(i: TransferItem) = viewModelScope.launch { db.transfers().sendNow(i.id); Scheduler.kick(a) }
    fun pauseAll(hours: Int = 0) = viewModelScope.launch {
        db.transfers().pauseAll()
        if (hours > 0) prefs.setPausedUntil(System.currentTimeMillis() + hours * 3600_000L)
    }
    fun resumeAll() = viewModelScope.launch { prefs.setPausedUntil(0); db.transfers().resumeAll(); Scheduler.kick(a) }
    fun retryFailed() = viewModelScope.launch { db.transfers().retryAllFailed(); Scheduler.kick(a) }
    private suspend fun deleteStaged(items: List<TransferItem>) = withContext(Dispatchers.IO) {
        items.filter { it.sourceUri.contains("/staging/") || it.sourceUri.contains("/cache/") }
            .forEach { runCatching { java.io.File(android.net.Uri.parse(it.sourceUri).path!!).delete() } }
    }
    fun clearQueue() = viewModelScope.launch { deleteStaged(db.transfers().openFileItems()); db.transfers().clearOpen() }
    fun clearFailed() = viewModelScope.launch { deleteStaged(db.transfers().openFileItems().filter { it.state == TState.FAILED }); db.transfers().clearFailedItems() }
    fun clearDone() = viewModelScope.launch { db.transfers().clearDone() }

    // ---- rules / backup ----
    fun saveRule(r: RoutingRule) = viewModelScope.launch { if (r.id == 0L) db.rules().insert(r.copy(position = rules.value.size)) else db.rules().update(r) }
    fun deleteRule(r: RoutingRule) = viewModelScope.launch { db.rules().delete(r) }
    fun moveRule(r: RoutingRule, delta: Int) = viewModelScope.launch {
        val l = rules.value.toMutableList(); val i = l.indexOfFirst { it.id == r.id }; val j = i + delta
        if (i < 0 || j !in l.indices) return@launch
        l.add(j, l.removeAt(i))
        l.forEachIndexed { idx, x -> db.rules().update(x.copy(position = idx)) }
    }
    fun saveBackup(b: BackupSource) = viewModelScope.launch { if (b.id == 0L) db.backups().insert(b) else db.backups().update(b); Scheduler.backupNow(a) }
    fun deleteBackup(b: BackupSource) = viewModelScope.launch { db.backups().delete(b) }
    fun backupNow() = viewModelScope.launch { prefs.setWindowOverrideUntil(System.currentTimeMillis() + 3600_000L); Scheduler.backupNow(a) }

    fun toggleFav(destId: Long, path: String) = viewModelScope.launch {
        val ex = favourites.value.firstOrNull { it.destinationId == destId && it.path == path }
        if (ex != null) db.favourites().delete(ex.id) else db.favourites().insert(Favourite(destinationId = destId, path = path))
    }

    init { refreshOnline() }
}
