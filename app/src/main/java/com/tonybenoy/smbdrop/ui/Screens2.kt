package com.tonybenoy.smbdrop.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tonybenoy.smbdrop.data.*
import com.tonybenoy.smbdrop.routing.Router
import kotlinx.coroutines.launch

fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

@Composable
fun BackupScreen(vm: AppVm) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val backups by vm.backups.collectAsState(); val dests by vm.destinations.collectAsState(); val s by vm.settings.collectAsState()
    var edit by remember { mutableStateOf<BackupSource?>(null) }
    var pendingType by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var freeUpMsg by remember { mutableStateOf<String?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            edit = BackupSource(label = uri.lastPathSegment?.substringAfterLast(':') ?: "Folder", type = "FOLDER", treeUri = uri.toString(),
                destinationId = dests.firstOrNull()?.id ?: 0, pathTemplate = "Folders/{yyyy}/{MM}")
        }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val t = pendingType ?: return@rememberLauncherForActivityResult
        edit = BackupSource(label = if (t == "CAMERA") "Camera" else "Screenshots", type = t, destinationId = dests.firstOrNull()?.id ?: 0,
            pathTemplate = if (t == "CAMERA") "Photos/{yyyy}/{MM}" else "Screenshots/{yyyy}/{MM}")
    }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 140.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle(tr("Backup"), tr("One-way, under your rules"), hPad = 0.dp) }
        if (dests.isEmpty()) item { StatusCard(tr("Add a server first"), tr("Backup needs a destination."), warning()) }
        items(backups, key = { it.id }) { b ->
            Card28(onClick = { edit = b }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.label, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(buildString {
                            append(if (b.wifiOnly) tr("Wi-Fi") else tr("Any network")); if (b.chargingOnly) append(" · " + tr("charging"))
                            append(" · → ${dests.firstOrNull { it.id == b.destinationId }?.nickname ?: "routing rules"}")
                        }, style = MaterialTheme.typography.bodyMedium)
                        Text(if (b.lastRun > 0) trf("Last run %1\$s", java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(b.lastRun))) else tr("Not run yet"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(b.enabled, { vm.saveBackup(b.copy(enabled = it)) })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ showAdd = true }, shape = Pill) { Text(tr("Add source")) }
                FilledTonalButton({ vm.backupNow() }, shape = Pill) { Text(tr("Back up now")) }
            }
        }
        item {
            SectionLabel(tr("Free up space"))
            Text(tr("Delete phone copies of photos that are safely on the server and older than N days."), style = MaterialTheme.typography.bodyMedium)
            var days by remember { mutableStateOf("30") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(days, { days = it.filter(Char::isDigit) }, label = { Text(tr("Days")) }, singleLine = true, modifier = Modifier.width(100.dp))
                FilledTonalButton({
                    scope.launch {
                        val d = days.toIntOrNull() ?: 30
                        val old = vm.db.uploaded().olderThan(System.currentTimeMillis() - d * 86400_000L)
                        val uris = old.mapNotNull { u ->
                            val parsed = u.sourceUri?.let(Uri::parse) ?: return@mapNotNull null
                            // only if the remote copy is still the same size
                            parsed
                        }
                        if (uris.isEmpty()) freeUpMsg = tr("Nothing to free up") else if (Build.VERSION.SDK_INT >= 30) {
                            val pi = android.provider.MediaStore.createDeleteRequest(ctx.contentResolver, uris)
                            deleteLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build())
                            freeUpMsg = trf("%1\$d files ready to delete — confirm in the system dialog", uris.size)
                        } else freeUpMsg = tr("Needs Android 11 or newer")
                    }
                }, shape = Pill) { Text(tr("Review & delete")) }
            }
            freeUpMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (showAdd) GlassSheet({ showAdd = false }) {
        Text(tr("Add backup source"), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        listOf("CAMERA" to "Camera photos & videos", "SCREENSHOTS" to "Screenshots", "FOLDER" to "Pick a folder…").forEach { (t, l) ->
            TextButton({
                showAdd = false
                if (t == "FOLDER") folderPicker.launch(null) else { pendingType = t; permLauncher.launch(mediaPermissions()) }
            }, Modifier.fillMaxWidth()) { Text(l, Modifier.fillMaxWidth()) }
        }
    }
    edit?.let { b -> BackupEditor(vm, b, dests, { edit = null }) }
}

@Composable
fun BackupEditor(vm: AppVm, b0: BackupSource, dests: List<Destination>, onDismiss: () -> Unit) {
    var b by remember { mutableStateOf(b0) }
    var onlyNew by remember { mutableStateOf(b0.onlyNewFrom > 0 || b0.id == 0L) }
    var days by remember { mutableStateOf(if (b0.freeUpDays > 0) b0.freeUpDays.toString() else "") }
    GlassSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(b.label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(tr("Destination"))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(b.destinationId == 0L, { b = b.copy(destinationId = 0) }, { Text(tr("Use routing rules")) })
                dests.forEach { d -> FilterChip(b.destinationId == d.id, { b = b.copy(destinationId = d.id) }, { Text(d.nickname) }) }
            }
            if (b.destinationId != 0L) OutlinedTextField(b.pathTemplate, { b = b.copy(pathTemplate = it) }, label = { Text(tr("Folder template")) }, supportingText = { Text(tr("Tokens: {yyyy} {MM} {dd} {name} {device}")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SwitchRow(tr("Wi-Fi only"), tr("Never use mobile data"), b.wifiOnly) { b = b.copy(wifiOnly = it) }
            SwitchRow(tr("Only while charging"), null, b.chargingOnly) { b = b.copy(chargingOnly = it) }
            if (b.id == 0L || b.onlyNewFrom == 0L) SwitchRow(tr("Only new files from now"), tr("Skip everything already on the phone"), onlyNew) { onlyNew = it }
            OutlinedTextField(days, { days = it.filter(Char::isDigit) }, label = { Text(tr("Free up phone copies after N days (empty = never)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    val nb = b.copy(onlyNewFrom = if (onlyNew && b.onlyNewFrom == 0L) System.currentTimeMillis() / 1000 else if (!onlyNew) 0 else b.onlyNewFrom, freeUpDays = days.toIntOrNull() ?: 0)
                    vm.saveBackup(nb); onDismiss()
                }, shape = Pill) { Text(tr("Save")) }
                if (b.id != 0L) TextButton({ vm.deleteBackup(b); onDismiss() }) { Text(tr("Remove"), color = danger()) }
            }
        }
    }
}

@Composable
fun RuleEditor(vm: AppVm, r0: RoutingRule, dests: List<Destination>, onDismiss: () -> Unit) {
    var r by remember { mutableStateOf(r0) }
    var browse by remember { mutableStateOf(false) }
    val favs by com.tonybenoy.smbdrop.App.instance.db.favourites().all().collectAsState(emptyList())
    val sample = com.tonybenoy.smbdrop.routing.FileInfo("IMG_0001.jpg", "image/jpeg", System.currentTimeMillis())
    GlassSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (r.id == 0L) tr("New rule") else tr("Edit rule"), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("MIME" to "File type", "EXT" to "Extension", "APP" to "From app").forEach { (k, l) -> FilterChip(r.kind == k, { r = r.copy(kind = k) }, { Text(tr(l)) }) }
            }
            OutlinedTextField(r.match, { r = r.copy(match = it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(when (r.kind) { "MIME" -> "MIME types (image/, video/, application/pdf)"; "EXT" -> "Extensions (pdf, docx)"; else -> "App package (com.example.scanner)" }) })
            Text(tr("Destination"))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dests.forEach { d -> FilterChip(r.destinationId == d.id, { r = r.copy(destinationId = d.id) }, { Text(d.nickname) }) }
            }
            OutlinedTextField(r.pathTemplate, { r = r.copy(pathTemplate = it) }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(tr("Folder template")) })
            BookmarkChips(dests, favs) { d, p -> r = r.copy(destinationId = d.id, pathTemplate = p.ifEmpty { "Drop" }) }
            TextButton({ browse = true }, enabled = r.destinationId != 0L) { Text(tr("Browse folders…")) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("{yyyy}", "{MM}", "{dd}", "{device}", "{ext}").forEach { t -> AssistChip({ r = r.copy(pathTemplate = r.pathTemplate.trimEnd('/') + "/" + t) }, { Text(t) }) }
            }
            dests.firstOrNull { it.id == r.destinationId }?.let { d -> if (browse) FolderPickerSheet(d, "", { r = r.copy(pathTemplate = it.ifEmpty { "Drop" }); browse = false }, { browse = false }) }
            Text(trf("Preview: %1\$s", "${dests.firstOrNull { it.id == r.destinationId }?.nickname ?: "?"} › ${com.tonybenoy.smbdrop.routing.Templates.render(r.pathTemplate, sample)}"), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.saveRule(r); onDismiss() }, enabled = r.match.isNotBlank() && r.destinationId != 0L, shape = Pill) { Text(tr("Save")) }
                if (r.id != 0L) TextButton({ vm.deleteRule(r); onDismiss() }) { Text(tr("Delete"), color = danger()) }
            }
        }
    }
}
