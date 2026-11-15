package com.tonybenoy.smbdrop.routing

import com.tonybenoy.smbdrop.ui.tr
import com.tonybenoy.smbdrop.ui.trf

import com.tonybenoy.smbdrop.data.Destination
import com.tonybenoy.smbdrop.data.RoutingRule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Names {
    private val illegal = Regex("[<>:\"/\\\\|?*\\x00-\\x1F]")

    /** Make a file name safe for Windows/SMB shares; keeps the extension when truncating to 255. */
    fun sanitize(name: String): String {
        var n = illegal.replace(name, "_").trimEnd('.', ' ').trimStart(' ')
        if (n.isEmpty()) n = "file"
        if (n.length > 255) {
            val ext = n.substringAfterLast('.', "").take(20)
            val stem = if (ext.isEmpty()) n else n.dropLast(ext.length + 1)
            n = stem.take(255 - ext.length - 1) + if (ext.isEmpty()) "" else ".$ext"
        }
        val reserved = setOf("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "LPT1", "LPT2")
        if (n.substringBefore('.').uppercase() in reserved) n = "_$n"
        return n
    }

    fun sanitizePath(p: String): String =
        p.split('/', '\\').filter { it.isNotBlank() }.joinToString("/") { sanitize(it) }

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun stem(name: String) = if (name.contains('.')) name.substringBeforeLast('.') else name

    /** "photo.jpg" -> "photo (2).jpg" */
    fun numbered(name: String, n: Int): String {
        val e = ext(name)
        return if (e.isEmpty()) "${stem(name)} ($n)" else "${stem(name)} ($n).${name.substringAfterLast('.')}"
    }
}

data class FileInfo(
    val name: String,
    val mime: String?,
    val dateMillis: Long,
    val sourcePackage: String? = null,
)

data class Route(val destinationId: Long, val dir: String, val matchedRule: Long? = null)

object Templates {
    /** Tokens: {yyyy} {MM} {dd} {HH} {name} {ext} {device} */
    fun render(template: String, f: FileInfo, device: String = android.os.Build.MODEL ?: "phone"): String {
        val d = Date(if (f.dateMillis > 0) f.dateMillis else System.currentTimeMillis())
        fun fmt(p: String) = SimpleDateFormat(p, Locale.US).format(d)
        val out = template
            .replace("{yyyy}", fmt("yyyy")).replace("{MM}", fmt("MM")).replace("{dd}", fmt("dd"))
            .replace("{HH}", fmt("HH"))
            .replace("{name}", Names.stem(f.name)).replace("{ext}", Names.ext(f.name))
            .replace("{device}", device)
        return Names.sanitizePath(out)
    }
}

object Router {
    const val FALLBACK_DIR = "Drop"

    fun matches(rule: RoutingRule, f: FileInfo): Boolean {
        val items = rule.match.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return false
        return when (rule.kind) {
            "MIME" -> f.mime?.lowercase()?.let { m -> items.any { m == it || (it.endsWith("/") && m.startsWith(it)) } } ?: false
            "EXT" -> Names.ext(f.name) in items.map { it.trimStart('.') }
            "APP" -> f.sourcePackage?.lowercase() in items
            else -> false
        }
    }

    /** First enabled matching rule wins; otherwise the default destination, or null (ask the user). */
    fun resolve(rules: List<RoutingRule>, f: FileInfo, defaultDest: Long, askEveryTime: Boolean): Route? {
        for (r in rules) if (r.enabled && matches(r, f)) return Route(r.destinationId, Templates.render(r.pathTemplate, f), r.id)
        if (askEveryTime || defaultDest == 0L) return null
        return Route(defaultDest, FALLBACK_DIR)
    }

    fun describe(r: RoutingRule, dest: Destination?): String {
        val what = when (r.kind) {
            "MIME" -> r.match.split(',').joinToString(", ") { mimeLabel(it.trim()) }
            "EXT" -> r.match.split(',').joinToString(", ") { "." + it.trim().trimStart('.') }
            else -> trf("Files from %1\$s", r.match)
        }
        return "$what → ${dest?.nickname ?: "?"} › ${r.pathTemplate}"
    }

    private fun mimeLabel(m: String) = when (m) {
        "image/" -> tr("Photos"); "video/" -> tr("Videos"); "audio/" -> tr("Audio"); "application/pdf" -> tr("PDFs"); "text/" -> tr("Text")
        else -> m
    }
}
