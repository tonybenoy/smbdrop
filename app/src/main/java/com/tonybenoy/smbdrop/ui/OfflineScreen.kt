package com.tonybenoy.smbdrop.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.tonybenoy.smbdrop.data.OfflineItem
import com.tonybenoy.smbdrop.routing.Names
import com.tonybenoy.smbdrop.transfer.OfflineWorker
import kotlinx.coroutines.launch
import java.io.File

/** Pinned files and folders, readable with no connection to the server. */
@Composable
fun OfflineScreen(vm: AppVm, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by vm.app.db.offline().all().collectAsState(emptyList())
    val dests by vm.destinations.collectAsState()
    var dir by remember { mutableStateOf<File?>(null) }     // folder being browsed (null = pinned list)
    val base = remember { File(ctx.filesDir, "offline") }
    BackHandler { if (dir != null && dir != base) { dir = dir!!.parentFile.takeIf { it != base }?.takeIf { it.path.startsWith(base.path) }; if (dir == null) dir = null } else onClose() }

    fun open(f: File) {
        val uri = FileProvider.getUriForFile(ctx, "com.tonybenoy.smbdrop.files", f)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(Names.ext(f.name)) ?: "*/*"
        runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), tr("Open with"))) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Filled.ArrowBack, tr("Back")) }
            Text(tr("Available offline"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton({ OfflineWorker.syncNow(ctx) }) { Text(tr("Sync now")) }
        }
        if (dir == null) {
            if (items.isEmpty()) EmptyState("📥", tr("Nothing saved offline yet"), hint = tr("Select files or folders in Browse and tap “Keep offline”."))
            LazyColumn(contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 140.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { o ->
                    val local = OfflineWorker.localRoot(ctx, o.destinationId, o.path)
                    Card28(Modifier.animateItem(), onClick = { if (o.isDir) dir = local else if (local.isFile) open(local) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (o.isDir) "📁" else "📄"); Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(o.name, style = MaterialTheme.typography.titleSmall)
                                Text(buildString {
                                    append(dests.firstOrNull { it.id == o.destinationId }?.nickname ?: "?"); append(" · "); append(fmtBytes(o.bytes))
                                    append(" · "); append(if (o.lastSync == 0L) tr("Not synced yet") else trf("Synced %1\$s", java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(o.lastSync))))
                                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                o.error?.let { Text(tr(it), style = MaterialTheme.typography.bodySmall, color = danger()) }
                            }
                            TextButton({ scope.launch { vm.app.db.offline().delete(o); local.deleteRecursively() } }) { Text(tr("Remove"), color = danger()) }
                        }
                    }
                }
            }
        } else {
            val files = remember(dir, items) { dir!!.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }) ?: emptyList() }
            LazyColumn(contentPadding = PaddingValues(bottom = 140.dp)) {
                items(files.filter { !it.name.endsWith(".part") }, key = { it.path }) { f ->
                    Row(Modifier.fillMaxWidth().clickable { if (f.isDirectory) dir = f else open(f) }.padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (f.isDirectory) "📁" else "📄"); Text(f.name, Modifier.weight(1f)); if (f.isFile) Text(fmtBytes(f.length()), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (files.isEmpty()) item { EmptyState("📂", tr("This folder is empty")) }
            }
        }
    }
}
