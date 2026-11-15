package com.tonybenoy.smbdrop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tonybenoy.smbdrop.data.*
import com.tonybenoy.smbdrop.transfer.Conditions
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(vm: AppVm, openQueue: () -> Unit, openSettingsTab: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val open by vm.open.collectAsState(); val dests by vm.destinations.collectAsState()
    val online by vm.online.collectAsState(); val today by vm.uploadedToday.collectAsState()
    val backups by vm.backups.collectAsState(); val s by vm.settings.collectAsState()
    val done by vm.recentDone.collectAsState()
    LaunchedEffect(dests.size) { vm.refreshOnline() }
    val active = open.filter { it.state == TState.ACTIVE }
    val pending = open.filter { it.state == TState.QUEUED || it.state == TState.ACTIVE || it.state == TState.WAITING }
    val failed = open.count { it.state == TState.FAILED }
    val paused = s.pausedUntil > System.currentTimeMillis()
    val totalLeft = pending.sumOf { maxOf(it.size - it.bytesDone, 0) }
    val speed = active.sumOf { it.speed }
    val status = when {
        paused -> tr("Paused")
        pending.isNotEmpty() -> trf("Uploading %1\$d of %2\$d", active.size.coerceAtLeast(1), pending.size) + if (speed > 0 && totalLeft > 0) " · " + trf("%1\$s left", fmtEta(totalLeft / speed)) else ""
        failed > 0 -> trf("%1\$d need attention", failed)
        else -> tr("All backed up")
    }
    var batteryRestricted by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        batteryRestricted = !pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
    val homeScope = rememberCoroutineScope()
    var notifOk by remember { mutableStateOf(androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()) }
    fun openNotifSettings() = ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        notifOk = granted || androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        if (!granted && !notifOk) openNotifSettings()   // denied for good → send the user to the settings page
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 140.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle(status, if (today > 0) (if (today == 1) tr("1 file uploaded in the last 24 h") else trf("%1\$d files uploaded in the last 24 h", today)) else "SMB Drop") }
        if (!notifOk) item { Box(Modifier.padding(horizontal = 20.dp)) {
            StatusCard(tr("Notifications are off"), tr("Transfer progress won't show."), warning(), tr("Allow notifications")) {
                if (android.os.Build.VERSION.SDK_INT >= 33) notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) else openNotifSettings()
            } } }
        if (batteryRestricted && !s.hideBatteryBanner && Build_isAggressive()) item { Column(Modifier.padding(horizontal = 20.dp)) {
            StatusCard(tr("Battery restrictions may stop transfers"), "On ${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} phones, allow SMB Drop to run unrestricted in the background.", warning(), tr("Fix it")) {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            TextButton({ homeScope.launch { vm.prefs.setHideBatteryBanner(true) } }) { Text(tr("Already done — don't show again")) }
        } }
        if (dests.isEmpty()) item { EmptyState("🔎", tr("Let's find your NAS or PC"), tr("Scan network")) { openSettingsTab() } }
        item {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Card28(Modifier.weight(1f), onClick = openQueue) {
                    Text(tr("Queue"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(trf("%1\$d active", active.size), fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                    Text(trf("%1\$d waiting · %2\$d failed", pending.size - active.size, failed), style = MaterialTheme.typography.bodyMedium)
                }
                Card28(Modifier.weight(1f)) {
                    Text(tr("Backup"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(trf("%1\$d today", today), fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                    Text(if (backups.none { it.enabled }) tr("Not set up") else trf("%1\$d sources on", backups.count { it.enabled }), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (dests.isNotEmpty()) item {
            Card28(Modifier.padding(horizontal = 20.dp)) {
                Text(tr("Servers"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                dests.forEach { d ->
                    val on = online[d.id]
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(when (on) { true -> success(); false -> danger(); else -> Color.Gray }))
                        Text(d.nickname, Modifier.weight(1f)); Text(when (on) { true -> tr("Online"); false -> tr("Offline"); else -> "…" }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ if (paused || open.any { it.state == TState.PAUSED }) vm.resumeAll() else vm.pauseAll(1) }, shape = Pill) {
                    Text(if (paused || open.any { it.state == TState.PAUSED }) tr("Resume all") else tr("Pause 1 h"))
                }
                FilledTonalButton({ vm.backupNow() }, shape = Pill) { Text(tr("Back up now")) }
                if (failed > 0) FilledTonalButton({ vm.retryFailed() }, shape = Pill) { Text(tr("Retry failed")) }
            }
        }
        if (done.isNotEmpty()) {
            item { Box(Modifier.padding(horizontal = 20.dp)) { SectionLabel(tr("Recent uploads")) } }
            items(done.take(8), key = { it.id }) { i ->
                Row(Modifier.animateItem().padding(horizontal = 20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (i.direction == "up") "✓" else "⬇", color = success())
                    Column(Modifier.weight(1f)) {
                        Text(i.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(fmtBytes(i.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DestChip(dests.firstOrNull { it.id == i.destinationId })
                }
            }
        }
    }
}

private fun Build_isAggressive() = android.os.Build.MANUFACTURER.lowercase() in setOf("xiaomi", "oppo", "vivo", "oneplus", "samsung", "huawei", "realme", "honor")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(vm: AppVm, onDismiss: () -> Unit) {
    val open by vm.open.collectAsState(); val done by vm.recentDone.collectAsState(); val dests by vm.destinations.collectAsState()
    var filter by remember { mutableStateOf("Active") }
    var confirmClear by remember { mutableStateOf(false) }
    val list = when (filter) {
        "Active" -> open.filter { it.state == TState.ACTIVE }
        "Waiting" -> open.filter { it.state == TState.QUEUED || it.state == TState.WAITING || it.state == TState.PAUSED }
        "Failed" -> open.filter { it.state == TState.FAILED }
        else -> done
    }
    GlassSheet(onDismiss) {
        Text(tr("Transfers"), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Active", "Waiting", "Failed", "Done").forEach { f ->
                FilterChip(filter == f, { filter = f }, { Text(tr(f)) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ vm.pauseAll() }) { Text(tr("Pause all")) }
            TextButton({ vm.resumeAll() }) { Text(tr("Resume all")) }
            if (filter == "Failed") TextButton({ vm.retryFailed() }) { Text(tr("Retry all")) }
            if (filter == "Failed") TextButton({ vm.clearFailed() }) { Text(tr("Clear failed"), color = danger()) }
            if (filter != "Done" && open.isNotEmpty()) TextButton({ confirmClear = true }) { Text(tr("Clear queue"), color = danger()) }
            if (filter == "Done") TextButton({ vm.clearDone() }) { Text(tr("Clear")) }
        }
        if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
            title = { Text(tr("Clear the whole queue?")) },
            text = { Text(if (open.size == 1) tr("1 transfer will be cancelled and removed. Files already on the server stay there; partial uploads are cleaned up later.") else trf("%1\$d transfers will be cancelled and removed. Files already on the server stay there; partial uploads are cleaned up later.", open.size)) },
            confirmButton = { TextButton({ vm.clearQueue(); confirmClear = false }) { Text(tr("Clear queue"), color = danger()) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text(tr("Keep")) } })
        if (list.isEmpty()) EmptyState("☕", hint = tr("New transfers appear here."), title = when (filter) { "Done" -> tr("Nothing finished yet"); "Active" -> tr("Nothing active"); "Waiting" -> tr("Nothing waiting"); else -> tr("Nothing failed") })
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            items(list, key = { it.id }) { i ->
                val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = {
                    when (it) { SwipeToDismissBoxValue.EndToStart -> { vm.cancel(i); true }; SwipeToDismissBoxValue.StartToEnd -> { vm.sendNow(i); false }; else -> false }
                })
                SwipeToDismissBox(dismissState, modifier = Modifier.animateItem(), backgroundContent = {
                    Box(Modifier.fillMaxSize().background(if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) danger().copy(0.3f) else MaterialTheme.colorScheme.primary.copy(0.3f)).padding(16.dp),
                        contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart) {
                        Text(if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) tr("Cancel") else tr("Send now"))
                    }
                }) { TransferRow(vm, i, dests.firstOrNull { it.id == i.destinationId }) }
            }
        }
    }
}

@Composable
fun TransferRow(vm: AppVm, i: TransferItem, d: Destination?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st by vm.settings.collectAsState()
    val reason = if (i.state == TState.QUEUED) Conditions.blocker(ctx, st, i.mobileOk, i.needsCharging, i.origin == "backup") else null
    val (label0, color0) = StateLabel(i)
    val label = reason ?: label0
    val color = if (reason != null) warning() else color0
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(when (i.direction) { "up" -> "⬆"; "down" -> "⬇"; else -> "⇄" })
        Column(Modifier.weight(1f)) {
            Text(i.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (i.state == TState.ACTIVE || (i.bytesDone > 0 && i.state != TState.DONE)) {
                LinearProgressIndicator(progress = { if (i.size > 0) (i.bytesDone.toFloat() / i.size).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
            Text(buildString {
                append(label); if (i.state == TState.ACTIVE && i.speed > 0) append(" · ${fmtBytes(i.speed)}/s · " + trf("%1\$s left", fmtEta(maxOf(i.size - i.bytesDone, 0) / i.speed)))
                else if (i.size > 0) append(" · ${fmtBytes(i.size)}")
                if (d != null) append(" · ${d.nickname}")
            }, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = color)
        }
        when (i.state) {
            TState.ACTIVE, TState.QUEUED, TState.WAITING -> TextButton({ vm.pause(i) }) { Text(tr("Pause")) }
            TState.PAUSED -> TextButton({ vm.resume(i) }) { Text(tr("Resume")) }
            TState.FAILED -> TextButton({ vm.resume(i) }) { Text(tr("Retry")) }
            else -> {}
        }
    }
}

/** Add / edit a destination: discover → login → share → test. */
@Composable
fun AddServerFlow(vm: AppVm, existing: Destination? = null, onDone: (Long?) -> Unit) {
    var step by remember { mutableStateOf(if (existing != null) "login" else "pick") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var nick by remember { mutableStateOf(existing?.nickname ?: "") }
    var guest by remember { mutableStateOf(existing?.guest ?: false) }
    var user by remember { mutableStateOf(existing?.username ?: "") }
    var domain by remember { mutableStateOf(existing?.domain ?: "") }
    var pass by remember { mutableStateOf("") }
    var share by remember { mutableStateOf(existing?.share ?: "") }
    var base by remember { mutableStateOf(existing?.basePath ?: "") }
    var shares by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<TestResult?>(null) }
    val found by vm.found.collectAsState(); val scanning by vm.scanning.collectAsState()
    LaunchedEffect(Unit) { if (existing == null) vm.scan() }
    val colours = listOf(0xFF3D7BFF, 0xFF1F9D66, 0xFFC98A00, 0xFFD83A3A, 0xFF8E5BFF, 0xFF00A3B4).map { it.toInt() }
    var colour by remember { mutableStateOf(existing?.colour ?: colours[vm.destinations.value.size % colours.size]) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (step) {
            "pick" -> {
                Text(tr("Find your NAS or PC"), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                if (scanning) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text(tr("Scanning your network…")) }
                found.forEach { s -> Card28(onClick = { host = s.ip; nick = s.name; step = "login" }) { Text(s.name, fontWeight = FontWeight.Medium); Text(s.ip, style = MaterialTheme.typography.bodyMedium) } }
                if (!scanning && found.isEmpty()) StatusCard(tr("No servers found on this network"), tr("Make sure the phone is on the same Wi-Fi and SMB sharing is on. You can still enter the address."), warning(), tr("Scan again")) { vm.scan() }
                OutlinedTextField(host, { host = it }, label = { Text(tr("Server address or name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ if (nick.isBlank()) nick = host; step = "login" }, enabled = host.isNotBlank(), shape = Pill) { Text(tr("Continue")) }
                    TextButton({ onDone(null) }) { Text(tr("Cancel")) }
                }
            }
            "login" -> {
                Text(if (existing != null) tr("Edit login") else tr("Sign in"), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(nick, { nick = it }, label = { Text(tr("Nickname")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(host, { host = it }, label = { Text(tr("Address")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SwitchRow(tr("Guest (no password)"), null, guest) { guest = it }
                if (!guest) {
                    OutlinedTextField(user, { user = it }, label = { Text(tr("Username")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pass, { pass = it }, label = { Text(if (existing != null) tr("Password (leave empty to keep)") else tr("Password")) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(domain, { domain = it }, label = { Text(tr("Domain (optional)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Row { colours.forEach { c -> Box(Modifier.padding(4.dp).size(if (c == colour) 36.dp else 28.dp).clip(CircleShape).background(Color(c)).clickable { colour = c }) } }
                error?.let { Text(it, color = danger()) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button({
                        busy = true; error = null
                        vm.listShares(host, user, pass, domain, guest, existing?.secret) { r ->
                            busy = false
                            r.onSuccess { shares = it; step = "share" }.onFailure { e ->
                                val m = e.message ?: "Failed"
                                if (m.contains("Wrong username")) error = tr(m) else { shares = emptyList(); step = "share"; error = null }
                            }
                        }
                    }, enabled = host.isNotBlank() && !busy, shape = Pill) { Text(tr("Next")) }
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    TextButton({ onDone(null) }) { Text(tr("Cancel")) }
                }
            }
            "share" -> {
                Text(tr("Choose a share"), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                shares.forEach { s -> FilterChip(share == s, { share = s }, { Text(s) }, modifier = Modifier.heightIn(min = 48.dp)) }
                OutlinedTextField(share, { share = it }, label = { Text(tr("Share name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(base, { base = it }, label = { Text(tr("Folder inside the share (optional)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button({
                        busy = true
                        val d = Destination(existing?.id ?: 0, nick.ifBlank { host }, host, share, base.trim('/'), user, domain, guest, existing?.secret ?: "", colour)
                        vm.test(d, if (pass.isNotEmpty()) pass else if (existing != null) null else "") { r ->
                            busy = false; result = r; if (r.ok) { vm.saveDestination(d, pass.ifEmpty { null }) { id -> step = "ok" } } else error = r.message
                        }
                    }, enabled = share.isNotBlank() && !busy, shape = Pill) { Text(tr("Test & save")) }
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    TextButton({ step = "login" }) { Text(tr("Back")) }
                }
                error?.let { StatusCard(tr("Couldn't connect"), it, danger(), tr("Edit login")) { step = "login" } }
            }
            "ok" -> {
                Text(tr("✓ Connected"), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = success())
                result?.free?.takeIf { it >= 0 }?.let { Text(trf("%1\$s free on %2\$s", fmtBytes(it), share)) }
                Button({ onDone(vm.destinations.value.lastOrNull()?.id) }, shape = Pill) { Text(tr("Done")) }
            }
        }
    }
}
