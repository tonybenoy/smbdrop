package com.tonybenoy.smbdrop.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tonybenoy.smbdrop.data.*
import com.tonybenoy.smbdrop.routing.Router
import com.tonybenoy.smbdrop.transfer.Conditions
import kotlinx.coroutines.launch

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Text(tr(title), Modifier.padding(start = 8.dp, top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Card28(content = content)
    }
}

@Composable
fun SettingsScreen(vm: AppVm, onLockChanged: () -> Unit, onTutorial: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by vm.settings.collectAsState(); val dests by vm.destinations.collectAsState(); val rules by vm.rules.collectAsState()
    var addServer by remember { mutableStateOf(false) }
    var editDest by remember { mutableStateOf<Destination?>(null) }
    var ruleEdit by remember { mutableStateOf<RoutingRule?>(null) }
    var wifi by remember(s.wifiNames) { mutableStateOf(s.wifiNames) }
    var legal by remember { mutableStateOf<String?>(null) }
    var timeEdit by remember { mutableStateOf<String?>(null) }
    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 140.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ScreenTitle(tr("Settings"), hPad = 0.dp) }
        item { FilledTonalButton(onTutorial, shape = Pill) { Text(tr("Show tutorial")) } }

        item {
            SettingsCard("Destinations") {
                if (dests.isEmpty()) Text(tr("No destinations yet"), style = MaterialTheme.typography.bodyMedium)
                dests.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        DestChip(d, Modifier.weight(1f, fill = false))
                        Spacer(Modifier.weight(1f))
                        if (s.defaultDestination == d.id) Text(tr("default"), style = MaterialTheme.typography.labelMedium)
                        else TextButton({ scope.launch { vm.prefs.setDefaultDestination(d.id) } }) { Text(tr("Make default")) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton({ editDest = d }) { Text(tr("Edit")) }
                        TextButton({ vm.deleteDestination(d) }) { Text(tr("Remove"), color = danger()) }
                    }
                }
                Button({ addServer = true }, shape = Pill) { Text(tr("Add destination")) }
            }
        }

        item {
            SettingsCard("Routing rules") {
                Text(if (s.askEveryTime) tr("First matching rule wins. No match → ask every time.") else tr("First matching rule wins. No match → default destination, /Drop."), style = MaterialTheme.typography.bodyMedium)
                rules.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Router.describe(r, dests.firstOrNull { it.id == r.destinationId }), Modifier.weight(1f).clickableCompat { ruleEdit = r })
                        TextButton({ vm.moveRule(r, -1) }) { Text("↑") }; TextButton({ vm.moveRule(r, 1) }) { Text("↓") }
                        Switch(r.enabled, { vm.saveRule(r.copy(enabled = it)) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ ruleEdit = RoutingRule(kind = "MIME", match = "image/,video/", destinationId = dests.firstOrNull()?.id ?: 0, pathTemplate = "Photos/{yyyy}/{MM}") }, enabled = dests.isNotEmpty(), shape = Pill) { Text(tr("Add rule")) }
                    if (rules.isEmpty()) FilledTonalButton({
                        val d = dests.firstOrNull()?.id ?: return@FilledTonalButton
                        vm.saveRule(RoutingRule(kind = "MIME", match = "image/,video/", destinationId = d, pathTemplate = "Photos/{yyyy}/{MM}"))
                        vm.saveRule(RoutingRule(kind = "MIME", match = "application/pdf", destinationId = d, pathTemplate = "Documents/Inbox"))
                    }, shape = Pill) { Text(tr("Add defaults")) }
                }
                SwitchRow(tr("Ask every time when no rule matches"), null, s.askEveryTime) { scope.launch { vm.prefs.setAsk(it) } }
            }
        }

        item {
            SettingsCard("Network & power") {
                SwitchRow(tr("Allow mobile data"), tr("Off: uploads wait for Wi-Fi unless you choose \"send now\""), s.allowMobileData) { scope.launch { vm.prefs.setMobile(it) } }
                SwitchRow(tr("Backup only during a time window"), if (s.nightOnly) trf("Backup runs only between %1\$s and %2\$s", Conditions.fmtMin(s.backupStart), Conditions.fmtMin(s.backupEnd)) else tr("“Back up now” ignores the window for 1 hour"), s.nightOnly) { scope.launch { vm.prefs.setNightOnly(it) } }
                if (s.nightOnly) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { i, d ->
                        FilterChip(s.backupDays and (1 shl i) != 0, { scope.launch { vm.prefs.setBackupDays(s.backupDays xor (1 shl i)) } }, { Text(tr(d)) })
                    }
                }
                if (s.nightOnly) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ timeEdit = "start" }, shape = Pill) { Text(trf("From %1\$s", Conditions.fmtMin(s.backupStart))) }
                    FilledTonalButton({ timeEdit = "end" }, shape = Pill) { Text(trf("To %1\$s", Conditions.fmtMin(s.backupEnd))) }
                }
                OutlinedTextField(wifi, { wifi = it }, label = { Text(tr("Backup only on these Wi-Fi names (comma separated)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(tr("Needs location permission to read the network name. Empty = any network.")) })
                Row { TextButton({ scope.launch { vm.prefs.setWifiNames(wifi) }; if (wifi.isNotBlank()) locPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text(tr("Save")) }
                    TextButton({ locPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text(tr("Allow location")) } }
                Text(if (s.minBattery == 0) tr("Minimum battery for backup: off") else trf("Minimum battery for backup: %1\$d%%", s.minBattery))
                Slider(s.minBattery / 5f, { scope.launch { vm.prefs.setMinBattery((it * 5).toInt()) } }, valueRange = 0f..16f)
            }
        }

        item {
            SettingsCard("Appearance") {
                Text(tr("Theme")); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (t, l) -> FilterChip(s.theme == t, { scope.launch { vm.prefs.setTheme(t) } }, { Text(tr(l)) }) } }
                Text(tr("Glass intensity")); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("full" to "Full", "reduced" to "Reduced", "off" to "Off").forEach { (t, l) -> FilterChip(s.glass == t, { scope.launch { vm.prefs.setGlass(t) } }, { Text(tr(l)) }) } }
                Text(tr("Language")); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    I18n.languages.forEach { (code, name) -> FilterChip(s.language == code, { scope.launch { vm.prefs.setLanguage(code) } }, { Text(if (code == "system") tr(name) else name) }) } }
            }
        }

        item {
            SettingsCard("Notifications") {
                SwitchRow(tr("Progress"), null, s.notifyProgress) { scope.launch { vm.prefs.setNotify(progress = it) } }
                SwitchRow(tr("Completed"), null, s.notifyDone) { scope.launch { vm.prefs.setNotify(done = it) } }
                SwitchRow(tr("Errors"), null, s.notifyErrors) { scope.launch { vm.prefs.setNotify(err = it) } }
                SwitchRow(tr("Backup summaries"), null, s.notifyBackup) { scope.launch { vm.prefs.setNotify(backup = it) } }
            }
        }

        item {
            SettingsCard("Security") {
                SwitchRow(tr("Lock with fingerprint"), tr("Also hides the app from screenshots and recents"), s.biometricLock) { scope.launch { vm.prefs.setBiometric(it); onLockChanged() } }
            }
        }

        item {
            SettingsCard("Advanced") {
                Text(trf("Concurrent transfers: %1\$d", s.concurrency)); Slider(s.concurrency.toFloat(), { scope.launch { vm.prefs.setConcurrency(it.toInt()) } }, valueRange = 1f..4f, steps = 2)
                SwitchRow(tr("Verify with SHA-256"), tr("Re-reads files under 200 MB after upload"), s.verifyHash) { scope.launch { vm.prefs.setVerifyHash(it) } }
                Text(tr("SMB 2 and 3 only. SMB1 is not supported for security reasons."), style = MaterialTheme.typography.bodySmall)
                TextButton({
                    val f = java.io.File(ctx.cacheDir, "smbdrop-log.txt")
                    f.writeText(buildString {
                        appendLine("SMB Drop log — ${java.util.Date()}"); appendLine("Android ${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}")
                        vm.open.value.forEach { appendLine("${it.state} ${it.name} ${it.bytesDone}/${it.size} attempts=${it.attempts} err=${it.error}") }
                    })
                    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "com.tonybenoy.smbdrop.files", f)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), tr("Export log")))
                }) { Text(tr("Export log")) }
            }
        }

        item {
            SettingsCard("About") {
                val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
                Text("SMB Drop $ver", style = MaterialTheme.typography.titleSmall)
                Text(tr("No analytics. No ads. Your files go only to your own server."), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton({ legal = "privacy" }) { Text(tr("Privacy policy")) }
                    TextButton({ legal = "licenses" }) { Text(tr("Open-source licences")) }
                }
            }
        }
    }

    if (addServer || editDest != null) GlassSheet({ addServer = false; editDest = null }) {
        AddServerFlow(vm, editDest) { addServer = false; editDest = null }
    }
    ruleEdit?.let { r -> RuleEditor(vm, r, dests) { ruleEdit = null } }
    timeEdit?.let { which ->
        val initial = if (which == "start") s.backupStart else s.backupEnd
        TimeDialog(tr(if (which == "start") "Start time" else "End time"), initial, { timeEdit = null }) { m ->
            scope.launch { if (which == "start") vm.prefs.setBackupWindow(m, s.backupEnd) else vm.prefs.setBackupWindow(s.backupStart, m) }
            timeEdit = null
        }
    }
    legal?.let { kind ->
        val text = remember(kind, I18n.effective()) { runCatching { ctx.assets.open(if (kind == "privacy") "privacy.txt" else "licenses.txt").bufferedReader().use { it.readText() } }.getOrDefault("") }
        AlertDialog(onDismissRequest = { legal = null }, title = { Text(tr(if (kind == "privacy") "Privacy policy" else "Open-source licences")) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text, style = MaterialTheme.typography.bodyMedium) } },
            confirmButton = { TextButton({ legal = null }) { Text(tr("Close")) } })
    }
}

private fun Modifier.clickableCompat(onClick: () -> Unit) = this.clickable(onClick = onClick)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, initialMin: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val ctx = LocalContext.current
    val state = rememberTimePickerState(initialMin / 60, initialMin % 60, android.text.format.DateFormat.is24HourFormat(ctx))
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { TimePicker(state) },
        confirmButton = { TextButton({ onPick(state.hour * 60 + state.minute) }) { Text(tr("OK")) } },
        dismissButton = { TextButton(onDismiss) { Text(tr("Cancel")) } })
}
