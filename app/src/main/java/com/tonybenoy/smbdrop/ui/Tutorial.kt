package com.tonybenoy.smbdrop.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private data class Step(val emoji: String, val title: String, val body: String, val tips: List<String>)

private val steps = listOf(
    Step("📤", "Send from any app", "Tap Share in Gallery, Files, WhatsApp or a browser, then pick SMB Drop.",
        listOf("Send = upload now. “Add to queue for later” holds it until you resume.",
            "Shared text or a link is saved as a .txt or .md note.",
            "Already sent this file? You'll be warned and can skip, keep both or replace.",
            "Options → send on mobile data, or pick a different folder just this once.")),
    Step("🧭", "Folders and bookmarks", "Every share goes to a folder. Rules choose it for you, and you can always change it.",
        listOf("“Browse folders…” on the share card lets you pick any folder on the server.",
            "Tap ★ in Browse or the folder picker to bookmark a folder.",
            "Bookmarks show as one-tap chips on the share card and in the rule editor.",
            "Pinned destinations also appear in the share sheet's direct-share row.")),
    Step("🛤", "Routing rules", "Settings → Routing rules sends files to the right place automatically.",
        listOf("Match by file type, extension, or the app that shared it.",
            "Folder templates: {yyyy} {MM} {dd} {device} {ext}. Example: Photos/{yyyy}/{MM}.",
            "First matching rule wins; reorder with the arrows.",
            "No rule matches → default destination, or ask every time.")),
    Step("📶", "The queue", "Tap the pill above the tab bar to open the queue. Nothing fails silently.",
        listOf("Tabs: Active, Waiting, Failed, Done — with speed and time left.",
            "Swipe left to cancel, right to send that file first. Pause or resume one file or all.",
            "Wi-Fi drops? Uploads pause and continue from where they stopped.",
            "“Clear queue” removes everything pending; “Clear failed” removes just the failures.")),
    Step("☁️", "Automatic backup", "Backup tab → Add source: camera, screenshots, or any folder.",
        listOf("Choose only-new-from-now or everything already on the phone.",
            "Rules per source: Wi-Fi only, charging only. Global: night window, chosen Wi-Fi, minimum battery.",
            "Backs up in batches and never uploads the same photo twice.",
            "“Free up space” deletes phone copies older than N days after you confirm.")),
    Step("🗂", "Browse your server", "The Browse tab is a full file manager for your shares.",
        listOf("List or grid, thumbnails, sort by name/date/size, search the folder.",
            "Tap a file to open it in another app; long-press to select several.",
            "Download, share, rename, move, delete (deleting asks first), create folders.",
            "Upload button adds files straight into the folder you're viewing.")),
    Step("📎", "Use your NAS inside other apps", "SMB Drop appears in Android's own file picker.",
        listOf("In Gmail, Docs or any app: Attach / Open → ☰ → SMB Drop – your server.",
            "Save a file from another app straight to the share with “Save as”.",
            "Large files stream with seeking; photos get thumbnails.")),
    Step("🔒", "Security and privacy", "Passwords are encrypted with the phone's hardware-backed keystore.",
        listOf("Settings → Security: lock with fingerprint; screenshots are blocked while it's on.",
            "Guest shares and DOMAIN\\user logins are supported; SMB 2/3 only.",
            "No analytics. “Export log” shares a troubleshooting file when you ask.")),
    Step("⚙️", "Settings and tips", "Make it behave the way you like.",
        listOf("Glass intensity: full, reduced or off (also auto-reduces in battery saver).",
            "Concurrent transfers 1–4 and optional SHA-256 verification.",
            "Home → Pause 1 h stops everything temporarily.",
            "On OnePlus, Xiaomi, Samsung: allow SMB Drop to run unrestricted in the background.",
            "You can reopen this tutorial any time from Settings.")),
)

@Composable
fun Tutorial(onClose: () -> Unit) {
    val pager = rememberPagerState { steps.size }
    val scope = rememberCoroutineScope()
    BackHandler { onClose() }
    Aurora {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClose) { Text(if (pager.currentPage == steps.size - 1) tr("Close") else tr("Skip")) }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { i ->
                val s = steps[i]
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.emoji, fontSize = 64.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(tr(s.title), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text(tr(s.body), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    Column(Modifier.fillMaxWidth().glass(Shapes28).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        s.tips.forEach { t -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Text("•", color = MaterialTheme.colorScheme.primary); Text(tr(t)) } }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(steps.size) { i ->
                        val c by animateColorAsState(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "dot")
                        Box(Modifier.size(if (i == pager.currentPage) 10.dp else 8.dp).clip(CircleShape).background(c))
                    }
                }
                if (pager.currentPage > 0) TextButton({ scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) { Text(tr("Back")) }
                Button({ if (pager.currentPage == steps.size - 1) onClose() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, shape = Pill) {
                    Text(if (pager.currentPage == steps.size - 1) tr("Done") else tr("Next"))
                }
            }
        }
    }
}
