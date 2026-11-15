package com.tonybenoy.smbdrop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tonybenoy.smbdrop.App
import org.json.JSONObject
import java.util.Locale

/** Lightweight runtime translations: English source strings are the keys; tables live in assets/i18n/<lang>.json. */
object I18n {
    val languages = listOf("system" to "System default", "en" to "English", "es" to "Español", "de" to "Deutsch", "fr" to "Français", "hi" to "हिन्दी")
    /** "system" or a language code. Compose-observable, so changing it recomposes every screen. */
    var lang by mutableStateOf("system")
    private val tables = HashMap<String, Map<String, String>>()

    fun effective(): String {
        val l = if (lang == "system") Locale.getDefault().language else lang
        return if (languages.any { it.first == l }) l else "en"
    }

    private fun table(l: String): Map<String, String> = synchronized(tables) {
        tables.getOrPut(l) {
            runCatching {
                val txt = App.instance.assets.open("i18n/$l.json").bufferedReader().use { it.readText() }
                val o = JSONObject(txt); o.keys().asSequence().associateWith { o.getString(it) }
            }.getOrDefault(emptyMap())
        }
    }

    fun tr(s: String): String { val l = effective(); return if (l == "en") s else table(l)[s] ?: s }
}

fun tr(s: String): String = I18n.tr(s)
/** Translate a template, then format it: trf("%1\$d files", n). */
fun trf(s: String, vararg a: Any): String = runCatching { String.format(I18n.tr(s), *a) }.getOrDefault(s)
