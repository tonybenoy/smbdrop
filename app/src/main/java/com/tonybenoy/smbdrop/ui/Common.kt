package com.tonybenoy.smbdrop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tonybenoy.smbdrop.data.Destination
import com.tonybenoy.smbdrop.data.TState
import com.tonybenoy.smbdrop.data.TransferItem
import com.tonybenoy.smbdrop.transfer.TransferWorker

fun fmtBytes(b: Long) = TransferWorker.fmt(b)
fun fmtEta(secs: Long): String = when { secs < 60 -> trf("%1\$d s", secs); secs < 3600 -> trf("%1\$d min", secs / 60); else -> trf("%1\$d h %2\$d min", secs / 3600, (secs % 3600) / 60) }

data class Tab(val label: String, val icon: ImageVector)
val Tabs = listOf(Tab("Home", Icons.Filled.Home), Tab("Browse", Icons.Filled.Search), Tab("Backup", Icons.Filled.Refresh), Tab("Settings", Icons.Filled.Settings))

/** Floating pill-shaped glass navigation bar, 12 dp above the system bar. */
@Composable
fun GlassNavBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(bottom = 12.dp).navigationBarsPadding().glass(Pill).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Tabs.forEachIndexed { i, t ->
            val sel = i == selected
            val bg by animateColorAsState(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent, label = "nav")
            Row(
                Modifier.clip(Pill).background(bg).clickable { onSelect(i) }.defaultMinSize(minWidth = 56.dp, minHeight = 48.dp)
                    .padding(horizontal = 14.dp).semantics { contentDescription = tr(t.label) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(t.icon, null, tint = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                AnimatedVisibility(sel) { Text(tr(t.label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

/** Always-visible compact pill showing the active queue; tap to expand. */
@Composable
fun TransferPill(items: List<TransferItem>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val active = items.filter { it.state == TState.ACTIVE }
    val waiting = items.count { it.state == TState.QUEUED || it.state == TState.WAITING }
    val failed = items.count { it.state == TState.FAILED }
    val paused = items.count { it.state == TState.PAUSED }
    val total = items.filter { it.state != TState.FAILED && it.state != TState.PAUSED }.sumOf { maxOf(it.size, 0) }
    val done = items.filter { it.state != TState.FAILED && it.state != TState.PAUSED }.sumOf { it.bytesDone }
    val frac = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    val anim by animateFloatAsState(frac, spring(Spring.DampingRatioMediumBouncy), label = "fill")
    val text = when {
        active.isNotEmpty() -> trf("Uploading %1\$d · %2\$s/s", active.size + waiting, fmtBytes(active.sumOf { it.speed }))
        waiting > 0 -> trf("%1\$d waiting", waiting)
        failed > 0 -> trf("%1\$d failed", failed)
        else -> trf("%1\$d paused", paused)
    }
    val g = LocalGlass.current
    Box(modifier.glass(Pill, 20.dp).clickable(onClick = onClick).semantics { contentDescription = trf("Transfer queue: %1\$s", text) }) {
        Box(Modifier.matchParentSize().fillMaxWidth(anim).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)).align(Alignment.CenterStart))
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (failed > 0 && active.isEmpty()) "⚠" else if (active.isNotEmpty()) "⬆" else "⏸")
            Text(text, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
        }
    }
}

@Composable
fun ScreenTitle(text: String, sub: String? = null, modifier: Modifier = Modifier, hPad: androidx.compose.ui.unit.Dp = 20.dp) {
    Column(modifier.statusBarsPadding().padding(horizontal = hPad, vertical = 16.dp)) {
        Text(text, style = MaterialTheme.typography.displayLarge)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Card28(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val m = modifier.fillMaxWidth().clip(Shapes28).background(MaterialTheme.colorScheme.surfaceContainer).let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Column(m.padding(18.dp), content = content)
}

/** Solid card with a coloured left edge for warnings/errors, each with one action. */
@Composable
fun StatusCard(title: String, body: String, color: Color, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).height(IntrinsicSize.Min)) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(color))
        Column(Modifier.padding(14.dp).weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) TextButton(onAction, contentPadding = PaddingValues(0.dp)) { Text(action) }
        }
    }
}

@Composable
fun DestChip(d: Destination?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val m = modifier.clip(Pill).background(MaterialTheme.colorScheme.surfaceContainerHigh).let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Row(m.padding(horizontal = 12.dp, vertical = 6.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(d?.colour ?: 0xFF888888.toInt())))
        Text(d?.nickname ?: tr("Choose destination"), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun StateLabel(i: TransferItem): Pair<String, Color> = when (i.state) {
    TState.ACTIVE -> tr("Uploading") to MaterialTheme.colorScheme.primary
    TState.QUEUED -> tr("Waiting") to MaterialTheme.colorScheme.onSurfaceVariant
    TState.WAITING -> tr(i.error ?: "Waiting for network") to warning()
    TState.PAUSED -> tr("Paused") to MaterialTheme.colorScheme.onSurfaceVariant
    TState.FAILED -> tr(i.error ?: "Failed") to danger()
    else -> tr("Done") to success()
}

@Composable
fun SwitchRow(title: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
fun SectionLabel(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))

/** True when the user turned animations off in Android settings; we then skip our own motion too. */
@Composable
fun rememberReduceMotion(): Boolean {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** Friendly empty state: a glass medallion that springs in, a title, an optional hint and one action. */
@Composable
fun EmptyState(emoji: String, title: String, action: String? = null, hint: String? = null, onAction: () -> Unit = {}) {
    val reduce = rememberReduceMotion()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(if (shown || reduce) 1f else 0.5f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow), label = "es")
    val alpha by animateFloatAsState(if (shown || reduce) 1f else 0f, tween(280), label = "ea")
    val bob by rememberInfiniteTransition(label = "bob").animateFloat(0f, if (reduce) 0f else -6f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bobv")
    Column(Modifier.fillMaxWidth().padding(32.dp).graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(104.dp).graphicsLayer { translationY = bob }.clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center) { Text(emoji, fontSize = 48.sp) }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) FilledTonalButton(onAction, shape = Pill) { Text(action) }
    }
}

/** Bottom sheet drawn as glass. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val g = LocalGlass.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = if (g.dark) Color(0xFF14161C).copy(alpha = if (g.off) 1f else 0.94f) else Color.White.copy(alpha = if (g.off) 1f else 0.94f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        content = { Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(), content = content) },
    )
}
