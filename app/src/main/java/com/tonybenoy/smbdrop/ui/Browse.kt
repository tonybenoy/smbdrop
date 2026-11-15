package com.tonybenoy.smbdrop.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tonybenoy.smbdrop.data.Destination
import com.tonybenoy.smbdrop.provider.Thumbs
import com.tonybenoy.smbdrop.routing.Names
import com.tonybenoy.smbdrop.smb.RemoteEntry
import com.tonybenoy.smbdrop.smb.toSmbError
import com.tonybenoy.smbdrop.transfer.Enqueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")

@Composable
fun BrowseScreen(vm: AppVm, openSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dests by vm.destinations.collectAsState(); val favs by vm.favourites.collectAsState(); val online by vm.online.collectAsState()
    var dest by remember { mutableStateOf<Destination?>(null) }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var grid by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf("name") }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var dialog by remember { mutableStateOf<String?>(null) }   // mkdir | rename | delete | move
    var text by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var offlineOpen by remember { mutableStateOf(false) }
    val offlineItems by vm.app.db.offline().all().collectAsState(emptyList())
    var copyStep by remember { mutableStateOf<String?>(null) }   // null | "dest" | "folder"
    var copyDest by remember { mutableStateOf<Destination?>(null) }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(dest?.id, path, reload) {
        val d = dest ?: return@LaunchedEffect
        loading = true; error = null
        withContext(Dispatchers.IO) {
            try { entries = vm.app.pool.use(d) { it.list(path) } } catch (e: Exception) { error = tr(e.toSmbError().userMessage); entries = emptyList() }
        }
        loading = false
    }
    LaunchedEffect(dest?.id) {
        val d = dest ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                vm.app.pool.use(d) { c ->
                    if (c.exists(com.tonybenoy.smbdrop.smb.TRASH)) c.list(com.tonybenoy.smbdrop.smb.TRASH).filter { System.currentTimeMillis() - (it.name.toLongOrNull() ?: 0L) > 24 * 3600_000L }
                        .forEach { c.delete(it.path, true) }
                }
            }
        }
    }
    BackHandler(dest != null) {
        if (selected.isNotEmpty()) selected = emptySet()
        else if (path.isNotEmpty()) path = path.substringBeforeLast('/', "") else dest = null
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val d = dest ?: return@rememberLauncherForActivityResult
        if (uris.isNotEmpty()) scope.launch {
            Enqueue.upload(ctx, uris.map { Enqueue.describe(ctx, it) }, d.id, path, trf("Upload to %1\$s", d.nickname), "browse")
        }
    }

    fun docUri(d: Destination, p: String) = DocumentsContract.buildDocumentUri("com.tonybenoy.smbdrop.documents", "${d.id}:$p")

    if (offlineOpen) { OfflineScreen(vm) { offlineOpen = false }; return }
    if (dest == null) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 140.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { ScreenTitle(tr("Browse")) }
            if (offlineItems.isNotEmpty()) item { Box(Modifier.padding(horizontal = 20.dp)) { Card28(onClick = { offlineOpen = true }) {
                Text("📥 " + tr("Available offline"), style = MaterialTheme.typography.titleSmall)
                Text(if (offlineItems.size == 1) tr("1 item saved on this phone") else trf("%1\$d items saved on this phone", offlineItems.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
            if (dests.isEmpty()) item { EmptyState("🔎", tr("Let's find your NAS or PC"), tr("Add a server")) { openSettings() } }
            items(dests, key = { "d${it.id}" }) { d ->
                Box(Modifier.padding(horizontal = 20.dp)) {
                    Card28(onClick = { dest = d; path = "" }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            DestChip(d)
                            Column(Modifier.weight(1f)) { Text("${d.host}/${d.share}", style = MaterialTheme.typography.bodyMedium) }
                            Text(when (online[d.id]) { true -> tr("Online"); false -> tr("Offline"); else -> "" }, color = if (online[d.id] == false) danger() else success())
                        }
                    }
                }
            }
            if (favs.isNotEmpty()) {
                item { Box(Modifier.padding(horizontal = 20.dp)) { SectionLabel(tr("Favourites")) } }
                items(favs, key = { "f${it.id}" }) { f ->
                    val d = dests.firstOrNull { it.id == f.destinationId } ?: return@items
                    Text("★ ${d.nickname} › ${f.path.ifEmpty { "/" }}", Modifier.fillMaxWidth().clickable { dest = d; path = f.path }.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
        }
        return
    }
    val d = dest!!
    val shown = entries.filter { query.isBlank() || it.name.contains(query, true) }
        .sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenComparator { a, b ->
            when (sort) { "date" -> b.modified.compareTo(a.modified); "size" -> b.size.compareTo(a.size); else -> a.name.compareTo(b.name, true) }
        })
    val isFav = favs.any { it.destinationId == d.id && it.path == path }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // glass top bar with breadcrumb
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).glass(Shapes28).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ if (path.isNotEmpty()) path = path.substringBeforeLast('/', "") else dest = null }) { Icon(Icons.Filled.ArrowBack, tr("Back")) }
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        Text(d.nickname, Modifier.clickable { path = "" }.padding(4.dp), fontWeight = FontWeight.SemiBold)
                        var acc = ""
                        path.split('/').filter { it.isNotEmpty() }.forEach { seg ->
                            acc = if (acc.isEmpty()) seg else "$acc/$seg"; val target = acc
                            Text(" › "); Text(seg, Modifier.clickable { path = target }.padding(4.dp))
                        }
                    }
                    IconButton({ searching = !searching; if (!searching) query = "" }) { Icon(Icons.Filled.Search, tr("Search")) }
                    IconButton({ vm.toggleFav(d.id, path) }) { Icon(Icons.Filled.Star, tr("Favourite"), tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) }
                }
                if (searching) OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(tr("Search this folder")) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("name", "date", "size").forEach { s -> FilterChip(sort == s, { sort = s }, { Text(s.replaceFirstChar { it.uppercase() }) }) }
                    Spacer(Modifier.weight(1f))
                    TextButton({ grid = !grid }) { Text(if (grid) tr("List") else tr("Grid")) }
                    TextButton({ text = ""; dialog = "mkdir" }) { Text(tr("New folder")) }
                }
                if (selected.isNotEmpty()) Text(trf("%1\$d selected", selected.size), fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                if (selected.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ selected = emptySet() }) { Text(tr("Clear")) }
                    TextButton({
                        selected.forEach { p -> entries.firstOrNull { it.path == p && !it.isDir }?.let { e -> scope.launch { Enqueue.download(ctx, d.id, e.path, e.name, e.size) } } }
                        selected = emptySet()
                    }) { Text(tr("Download")) }
                    TextButton({
                        val uris = selected.mapNotNull { p -> entries.firstOrNull { it.path == p && !it.isDir }?.let { docUri(d, it.path) } }
                        if (uris.isNotEmpty()) ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                    }) { Text(tr("Share")) }
                    if (selected.size == 1) TextButton({ text = entries.first { it.path == selected.first() }.name; dialog = "rename" }) { Text(tr("Rename")) }
                    TextButton({ text = path; dialog = "move" }) { Text(tr("Move")) }
                    TextButton({ copyStep = "dest" }) { Text(tr("Copy to…")) }
                    TextButton({
                        val picked = selected.mapNotNull { p -> entries.firstOrNull { it.path == p } }
                        selected = emptySet()
                        scope.launch {
                            picked.forEach { e -> if (vm.app.db.offline().find(d.id, e.path) == null) vm.app.db.offline().insert(com.tonybenoy.smbdrop.data.OfflineItem(destinationId = d.id, path = e.path, isDir = e.isDir, name = e.name)) }
                            com.tonybenoy.smbdrop.transfer.OfflineWorker.syncNow(ctx)
                            snack.showSnackbar(trf("Keeping %1\$d item(s) offline", picked.size))
                        }
                    }) { Text(tr("Keep offline")) }
                    TextButton({ dialog = "delete" }) { Text(tr("Delete"), color = danger()) }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Box(Modifier.padding(16.dp)) { StatusCard(tr("Couldn't open folder"), it, danger(), tr("Retry")) { reload++ } } }
            if (!loading && error == null && shown.isEmpty()) EmptyState("📂", tr("This folder is empty"), tr("Upload here")) { picker.launch("*/*") }

            fun click(e: RemoteEntry) {
                if (selected.isNotEmpty()) { selected = if (e.path in selected) selected - e.path else selected + e.path; return }
                if (e.isDir) { path = e.path; query = ""; searching = false } else {
                    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(Names.ext(e.name)) ?: "*/*"
                    runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(docUri(d, e.path), mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), tr("Open with"))) }
                }
            }
            if (grid) LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 150.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                gridItems(shown, key = { it.path }) { e ->
                    val sel = e.path in selected
                    Column(Modifier.clip(Shapes28).background(if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                        .combinedClickable(onClick = { click(e) }, onLongClick = { selected = selected + e.path }).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(90.dp).clip(RoundedCornerShapeSmall), contentAlignment = Alignment.Center) { FileThumb(vm, d, e, 90) }
                        Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else LazyColumn(contentPadding = PaddingValues(bottom = 150.dp)) {
                items(shown, key = { it.path }) { e ->
                    val sel = e.path in selected
                    Row(Modifier.animateItem().fillMaxWidth().background(if (sel) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                        .combinedClickable(onClick = { click(e) }, onLongClick = { selected = selected + e.path }).padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShapeSmall), contentAlignment = Alignment.Center) { FileThumb(vm, d, e, 44) }
                        Column(Modifier.weight(1f)) {
                            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (e.isDir) tr("Folder") else "${fmtBytes(e.size)} · ${java.text.DateFormat.getDateInstance().format(java.util.Date(e.modified))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp))
        ExtendedFloatingActionButton({ picker.launch("*/*") }, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 110.dp), shape = Pill) {
            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text(tr("Upload"))
        }
    }

    if (copyStep == "dest") GlassSheet({ copyStep = null }) {
        Text(tr("Copy to which server?"), style = MaterialTheme.typography.titleLarge)
        dests.forEach { x -> DestChip(x, Modifier.fillMaxWidth().padding(vertical = 4.dp)) { copyDest = x; copyStep = "folder" } }
    }
    if (copyStep == "folder") copyDest?.let { target ->
        FolderPickerSheet(target, "", { folder ->
            val picked = selected.mapNotNull { p -> entries.firstOrNull { it.path == p } }
            copyStep = null; selected = emptySet()
            scope.launch { Enqueue.copy(ctx, d, picked, target.id, folder, trf("Copy to %1\$s", target.nickname)) }
        }, { copyStep = null })
    }
    dialog?.let { which ->
        AlertDialog(onDismissRequest = { dialog = null },
            title = { Text(when (which) { "mkdir" -> tr("New folder"); "rename" -> tr("Rename"); "move" -> tr("Move to folder"); else -> trf("Delete %1\$d item(s)?", selected.size) }) },
            text = { if (which == "delete") Text(tr("They are removed from the server. You can undo for 5 seconds.")) else OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(if (which == "move") "Destination folder path" else "Name") }) },
            confirmButton = {
                TextButton({
                    val what = which; dialog = null
                    scope.launch(Dispatchers.IO) {
                        try {
                            vm.app.pool.use(d) { c ->
                                when (what) {
                                    "mkdir" -> c.mkdirs(listOf(path, Names.sanitize(text)).filter { it.isNotEmpty() }.joinToString("/"))
                                    "rename" -> entries.firstOrNull { it.path == selected.first() }?.let { e -> c.rename(e.path, listOf(path, Names.sanitize(text)).filter { it.isNotEmpty() }.joinToString("/"), e.isDir) }
                                    "move" -> { val target = Names.sanitizePath(text); c.mkdirs(target); selected.forEach { p -> entries.firstOrNull { it.path == p }?.let { e -> c.rename(e.path, listOf(target, e.name).filter { it.isNotEmpty() }.joinToString("/"), e.isDir) } } }
                                    "delete" -> {
                                        // Soft delete: move to a hidden trash folder so the user can undo for 5 seconds.
                                        val stamp = System.currentTimeMillis().toString()
                                        val trash = "${com.tonybenoy.smbdrop.smb.TRASH}/$stamp"
                                        c.mkdirs(trash)
                                        val moved = selected.mapNotNull { p -> entries.firstOrNull { it.path == p } }.map { e ->
                                            val tp = "$trash/${e.name}"; c.rename(e.path, tp, e.isDir); Triple(e, tp, trash)
                                        }
                                        scope.launch {
                                            val r = withTimeoutOrNull(5000) {
                                                snack.showSnackbar(moved.size.let { n -> if (n == 1) trf("Deleted %1\$s", moved[0].first.name) else trf("Deleted %1\$d items", n) }, tr("Undo"), duration = SnackbarDuration.Indefinite)
                                            }
                                            withContext(Dispatchers.IO) {
                                                runCatching {
                                                    vm.app.pool.use(d) { cc ->
                                                        if (r == SnackbarResult.ActionPerformed) moved.forEach { (e, tp, _) -> cc.rename(tp, e.path, e.isDir) }
                                                        cc.delete(trash, true)
                                                    }
                                                }
                                            }
                                            reload++
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) { error = tr(e.toSmbError().userMessage) }
                        selected = emptySet(); reload++
                    }
                }) { Text(if (which == "delete") tr("Delete") else tr("OK"), color = if (which == "delete") danger() else MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton({ dialog = null }) { Text(tr("Cancel")) } })
    }
}

private val RoundedCornerShapeSmall = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)

@Composable
private fun FileThumb(vm: AppVm, d: Destination, e: RemoteEntry, px: Int) {
    val ctx = LocalContext.current
    if (e.isDir) { Text("📁", fontSize = (px / 2).sp); return }
    val kind = Thumbs.kind(e.name)
    if (kind == null) { Text("📄", fontSize = (px / 2).sp); return }
    var bmp by remember(e.path) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(e.path) {
        bmp = withContext(Dispatchers.IO) {
            Thumbs.get(ctx, vm.app, d, e.path, 256)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
        }
    }
    val b = bmp
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (b != null) Image(b, e.name, Modifier.fillMaxSize(), contentScale = if (kind == "pdf") ContentScale.Fit else ContentScale.Crop)
        else Text(when (kind) { "video" -> "🎬"; "pdf" -> "📕"; else -> "🖼" }, fontSize = (px / 2).sp)
        if (kind == "video") Text("▶", Modifier.align(Alignment.BottomStart).padding(3.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(horizontal = 4.dp),
            color = androidx.compose.ui.graphics.Color.White, fontSize = (px / 5).coerceAtLeast(9).sp)
    }
}
