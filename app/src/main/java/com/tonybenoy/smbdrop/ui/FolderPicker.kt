package com.tonybenoy.smbdrop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tonybenoy.smbdrop.App
import com.tonybenoy.smbdrop.data.Destination
import com.tonybenoy.smbdrop.data.Favourite
import com.tonybenoy.smbdrop.routing.Names
import com.tonybenoy.smbdrop.smb.RemoteEntry
import com.tonybenoy.smbdrop.smb.toSmbError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Browse folders on a destination, bookmark them, and pick one as a target. */
@Composable
fun FolderPickerSheet(dest: Destination, startPath: String = "", onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(startPath.trim('/')) }
    var folders by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val favs by app.db.favourites().all().collectAsState(emptyList())
    val isFav = favs.any { it.destinationId == dest.id && it.path == path }

    LaunchedEffect(dest.id, path, reload) {
        loading = true; error = null
        withContext(Dispatchers.IO) {
            try { folders = app.pool.use(dest) { it.list(path) }.filter { it.isDir }.sortedBy { it.name.lowercase() } }
            catch (e: Exception) { folders = emptyList(); error = tr(e.toSmbError().userMessage) }
        }
        loading = false
    }

    GlassSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ if (path.isNotEmpty()) path = path.substringBeforeLast('/', "") else onDismiss() }) { Icon(Icons.Filled.ArrowBack, tr("Up")) }
            Column(Modifier.weight(1f)) {
                Text(tr("Choose folder"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(dest.nickname + " › " + path.replace("/", " › "), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            IconButton({
                scope.launch {
                    val ex = favs.firstOrNull { it.destinationId == dest.id && it.path == path }
                    if (ex != null) app.db.favourites().delete(ex.id) else app.db.favourites().insert(Favourite(destinationId = dest.id, path = path))
                }
            }) { Icon(Icons.Filled.Star, tr("Bookmark this folder"), tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { StatusCard(tr("Couldn't open folder"), it, danger(), tr("Retry")) { reload++ } }
        LazyColumn(Modifier.heightIn(max = 340.dp)) {
            items(folders, key = { it.path }) { f ->
                Row(Modifier.fillMaxWidth().clickable { path = f.path }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("📁"); Text(f.name, Modifier.weight(1f)); Text("›")
                }
            }
            if (!loading && error == null && folders.isEmpty()) item { Text(tr("No subfolders here"), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
        }
        if (newName != null) {
            OutlinedTextField(newName!!, { newName = it }, singleLine = true, label = { Text(tr("New folder name")) }, modifier = Modifier.fillMaxWidth())
            Row {
                TextButton({
                    val n = Names.sanitize(newName!!); newName = null
                    scope.launch(Dispatchers.IO) {
                        try { app.pool.use(dest) { it.mkdirs(listOf(path, n).filter { s -> s.isNotEmpty() }.joinToString("/")) } } catch (e: Exception) { error = tr(e.toSmbError().userMessage) }
                        reload++
                    }
                }) { Text(tr("Create")) }
                TextButton({ newName = null }) { Text(tr("Cancel")) }
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ onPick(path) }, shape = Pill) { Text(tr("Use this folder")) }
            TextButton({ newName = "" }) { Text(tr("New folder")) }
        }
    }
}

/** Row of bookmarked folders as chips; tapping one calls onPick(destination, path). */
@Composable
fun BookmarkChips(dests: List<Destination>, favs: List<Favourite>, onPick: (Destination, String) -> Unit) {
    if (favs.isEmpty()) return
    androidx.compose.foundation.layout.Column {
        Text(tr("Bookmarks"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            favs.forEach { f ->
                val d = dests.firstOrNull { it.id == f.destinationId } ?: return@forEach
                AssistChip({ onPick(d, f.path) }, { Text("★ ${d.nickname} › ${f.path.ifEmpty { "/" }}", maxLines = 1) })
            }
        }
    }
}

