package com.tonybenoy.smbdrop.share

import com.tonybenoy.smbdrop.ui.tr
import com.tonybenoy.smbdrop.ui.trf

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.tonybenoy.smbdrop.App
import com.tonybenoy.smbdrop.data.Settings
import androidx.compose.foundation.clickable
import com.tonybenoy.smbdrop.data.Destination
import com.tonybenoy.smbdrop.routing.*
import com.tonybenoy.smbdrop.transfer.Enqueue
import com.tonybenoy.smbdrop.transfer.SourceFile
import com.tonybenoy.smbdrop.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class ShareActivity : ComponentActivity() {
    private lateinit var app: App

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app = application as App
        val files = collect(intent)
        val srcPkg = referrer?.host
        val shortcutDest = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.removePrefix("dest_")?.toLongOrNull()
        val s = runBlocking { app.prefs.get() }
        if (Build.VERSION.SDK_INT >= 33 && !androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled())
            registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            SmbTheme(s.theme, s.glass, s.language) { ShareSheet(files, srcPkg, shortcutDest) { finish() } }
        }
    }

    /** Make every file readable after this screen closes; null if one can't be read. */
    private suspend fun stageAll(files: List<SourceFile>, status: (String?) -> Unit): List<SourceFile>? = try {
        val out = files.mapIndexed { i, f ->
            status("${f.name} (${i + 1}/${files.size})")
            Enqueue.stage(this, f) { b -> status("${f.name} · ${fmtBytes(b)}") }
        }
        status(null); out
    } catch (e: Exception) { null }

    @Suppress("DEPRECATION")
    private fun collect(i: Intent): List<SourceFile> {
        val out = ArrayList<SourceFile>()
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND_MULTIPLE -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: arrayListOf()
                else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: arrayListOf()
            else -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        }
        uris.forEach { out += Enqueue.describe(this, it, i.type) }
        val text = i.getStringExtra(Intent.EXTRA_TEXT)
        if (uris.isEmpty() && !text.isNullOrBlank()) {
            // Shared text or link becomes a note.
            val isUrl = Regex("^https?://\\S+$").matches(text.trim().lines().last().trim()) || text.trim().startsWith("http")
            val subject = i.getStringExtra(Intent.EXTRA_SUBJECT)
            val ext = if (isUrl) "md" else "txt"
            val title = (subject ?: text.lines().first().take(40)).ifBlank { "Note" }
            val name = Names.sanitize("$title.$ext")
            val body = if (isUrl) "# ${subject ?: "Link"}\n\n$text\n" else text
            val f = File(cacheDir, "note-${System.nanoTime()}-$name").also { it.writeText(body) }
            out += SourceFile(Uri.fromFile(f), name, f.length(), if (isUrl) "text/markdown" else "text/plain", System.currentTimeMillis())
        }
        return out
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ShareSheet(files: List<SourceFile>, srcPkg: String?, shortcutDest: Long?, close: () -> Unit) {
        val dests by app.db.destinations().all().collectAsState(emptyList())
        val rules by app.db.rules().all().collectAsState(emptyList())
        val s by app.prefs.flow.collectAsState(Settings())
        var chosen by remember { mutableStateOf<Long?>(null) }
        var pickDest by remember { mutableStateOf(false) }
        var options by remember { mutableStateOf(false) }
        var mobile by remember { mutableStateOf(false) }
        var conflict by remember { mutableStateOf("KEEP_BOTH") }
        var dupCount by remember { mutableIntStateOf(0) }
        var sent by remember { mutableStateOf<String?>(null) }
        var browse by remember { mutableStateOf(false) }
        var preparing by remember { mutableStateOf<String?>(null) }
        var failedMsg by remember { mutableStateOf<String?>(null) }
        val favs by app.db.favourites().all().collectAsState(emptyList())

        // Routed destination from the first file; overridden by Direct Share or a manual choice.
        val first = files.firstOrNull()
        val route = remember(rules, s, first, dests, shortcutDest) {
            if (first == null) null else {
                val r = Router.resolve(rules.filter { it.enabled }, FileInfo(first.name, first.mime, first.modified, srcPkg), s.defaultDestination, s.askEveryTime)
                if (shortcutDest != null) Route(shortcutDest, r?.dir ?: Router.FALLBACK_DIR) else r
            }
        }
        val destId = chosen ?: route?.destinationId ?: dests.firstOrNull()?.id
        val dest = dests.firstOrNull { it.id == destId }
        var dirOverride by remember { mutableStateOf<String?>(null) }
        val dir = dirOverride ?: route?.dir ?: Router.FALLBACK_DIR

        LaunchedEffect(files) {
            dupCount = withContext(Dispatchers.IO) { files.count { app.db.uploaded().get("uri:${it.uri}") != null } }
        }
        val total = files.sumOf { maxOf(it.size, 0) }

        Box(Modifier.fillMaxSize().clickableNoRipple { close() }, contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().clickableNoRipple {}.navigationBarsPadding().padding(12.dp).glass(Shapes28, strong = true).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (sent != null) {
                    val pop by androidx.compose.animation.core.animateFloatAsState(1f, androidx.compose.animation.core.spring(0.4f, androidx.compose.animation.core.Spring.StiffnessLow), label = "pop")
                    Text("✓ $sent", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = success(), modifier = Modifier.graphicsLayer { scaleX = pop; scaleY = pop })
                    LaunchedEffect(Unit) { kotlinx.coroutines.delay(900); close() }
                } else {
                    Text(if (files.isEmpty()) tr("Nothing to send") else (if (files.size == 1) trf("1 file · %1\$s", fmtBytes(total)) else trf("%1\$d files · %2\$s", files.size, fmtBytes(total))), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(files.take(3).joinToString(", ") { it.name } + if (files.size > 3) " …" else "", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    preparing?.let { Text(trf("Preparing %1\$s…", it), color = MaterialTheme.colorScheme.primary); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    failedMsg?.let { StatusCard(tr("Not sent"), it, danger()) }
                    if (dests.isEmpty()) {
                        StatusCard(tr("No server yet"), tr("Open SMB Drop and add a destination first."), warning(), tr("Open SMB Drop")) { startActivity(Intent(this@ShareActivity, MainActivity::class.java)); close() }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DestChip(dest)
                            Text("› " + dir.replace("/", " › "), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            TextButton({ pickDest = true }) { Text(tr("Change")) }
                        }
                        BookmarkChips(dests, favs) { d, p -> chosen = d.id; dirOverride = p }
                        TextButton({ browse = true }) { Text(tr("Browse folders…")) }
                        if (dupCount > 0) StatusCard("$dupCount already uploaded", tr("Choose what to do with files that were sent before."), warning())
                        TextButton({ options = !options }) { Text(if (options) tr("Hide options") else tr("Options")) }
                        AnimatedVisibility(options) {
                            Column {
                                Text(tr("If a file already exists"))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("SKIP" to "Skip", "KEEP_BOTH" to "Keep both", "REPLACE" to "Replace").forEach { (k, l) -> FilterChip(conflict == k, { conflict = k }, { Text(tr(l)) }) }
                                }
                                SwitchRow(tr("Send now even on mobile data"), null, mobile) { mobile = it }
                                OutlinedTextField(dir, { dirOverride = it }, singleLine = true, label = { Text(tr("Folder (just this once)")) }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({
                                lifecycleScope.launch {
                                    val skip = if (conflict == "SKIP") files.filter { app.db.uploaded().get("uri:${it.uri}") == null } else files
                                    val staged = stageAll(skip) { preparing = it } ?: run { failedMsg = tr("Couldn't read the shared file"); preparing = null; return@launch }
                                    Enqueue.upload(this@ShareActivity, staged, destId!!, Names.sanitizePath(dir), "Shared files", "share", conflict, mobile)
                                    sent = if (files.size == 1) trf("1 file queued to %1\$s", dest?.nickname ?: "") else trf("%1\$d files queued to %2\$s", files.size, dest?.nickname ?: "")
                                }
                            }, enabled = files.isNotEmpty() && destId != null, shape = Pill) { Text(tr("Send")) }
                            FilledTonalButton({
                                lifecycleScope.launch {
                                    val staged = stageAll(files) { preparing = it } ?: run { failedMsg = tr("Couldn't read the shared file"); preparing = null; return@launch }
                                    Enqueue.upload(this@ShareActivity, staged, destId!!, Names.sanitizePath(dir), "Shared files", "share", conflict, mobile, paused = true)
                                    sent = tr("Added to queue for later")
                                }
                            }, enabled = files.isNotEmpty() && destId != null, shape = Pill) { Text(tr("Add to queue for later")) }
                        }
                    }
                }
            }
        }
        if (browse && dest != null) FolderPickerSheet(dest, dir, { dirOverride = it; browse = false }, { browse = false })
        if (pickDest) ModalBottomSheet({ pickDest = false }) {
            Column(Modifier.padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Send to"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                dests.forEach { d -> DestChip(d, Modifier.fillMaxWidth()) { chosen = d.id; pickDest = false } }
            }
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit) =
    clickable(interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(), indication = null, onClick = onClick)
