package com.shilapi.xcertplay.ui.i18n

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * JSON-driven UI strings that follow the system language.
 *
 * English (`assets/i18n/en.json`) is the base table and always complete. Other languages are
 * optional overlays that only need the keys they translate; a missing key falls back to English.
 *
 * The language comes from the context's configuration: the user's ordered language list, which
 * on Android 13+ also reflects a per-app language chosen in system settings. The first language
 * in that list that has a translation wins (so a list of "fr, zh-CN, en" shows Chinese when only
 * zh-CN is translated); English is used when none has one. A system language change recreates
 * the activity, which calls [init] again; [refresh] covers activities that handle `locale`
 * configuration changes themselves.
 *
 * For the chosen language, overlays are applied from least to most specific tag:
 * `<language>`, `<language>-<Script>`, `<language>-<REGION>` (for example `zh`, `zh-Hans`, `zh-CN`).
 * For each tag, `assets/i18n/<tag>.json` is applied first and `<external files>/i18n/<tag>.json`
 * (side-loaded, no rebuild needed) on top of it. Anything passed to [inject] wins over both.
 *
 * Values may contain `{name}` placeholders, filled by [tr]. Keys are flat, dot-separated strings.
 */
object I18n {
    private const val TAG = "xcertplay-i18n"
    private const val DIRECTORY = "i18n"
    private const val BASE = "en"

    @Volatile private var table: Map<String, String> = emptyMap()
    @Volatile private var loaded = false
    @Volatile private var loadedFor: String? = null
    private val injected = LinkedHashMap<String, String>()
    private var appContext: Context? = null

    /** Bumped whenever the table changes so Compose readers recompose. */
    private val version = mutableIntStateOf(0)

    /** BCP 47 tag of the language whose table is in use (for example "zh-CN"), or "en". */
    @Volatile var activeLanguage: String = BASE
        private set

    /** Loads the strings for [context]'s current language list. Safe to call more than once. */
    fun init(context: Context) {
        load(context, preferredLocales(context))
        version.intValue++
    }

    /** Reloads only when the system language list differs from the one last loaded. */
    fun refresh(context: Context) {
        val locales = preferredLocales(context)
        if (signature(locales) == loadedFor) return
        init(context)
    }

    @Synchronized
    private fun load(context: Context, locales: List<Locale>) {
        val app = context.applicationContext
        appContext = app
        val external = app.getExternalFilesDir(null)?.let { File(it, DIRECTORY) }
        val merged = LinkedHashMap<String, String>()
        readAsset(app, BASE)?.let(merged::putAll)
        var chosen = BASE
        for (locale in locales) {
            if (locale.language.equals(BASE, ignoreCase = true)) break
            // Assets first, side-loaded files on top, each from least to most specific.
            val overlays = candidateTags(locale).flatMap { tag ->
                listOfNotNull(
                    readAsset(app, tag),
                    external?.let { readFile(File(it, "$tag.json")) },
                )
            }
            if (overlays.isEmpty()) continue
            overlays.forEach(merged::putAll)
            chosen = locale.toLanguageTag()
            break
        }
        merged.putAll(injected)
        table = merged
        activeLanguage = chosen
        loadedFor = signature(locales)
        loaded = true
    }

    private fun preferredLocales(context: Context): List<Locale> {
        val list = context.resources.configuration.locales
        return (0 until list.size()).map { list[it] }.ifEmpty { listOf(Locale.getDefault()) }
    }

    private fun signature(locales: List<Locale>): String = locales.joinToString(",") { it.toLanguageTag() }

    /** Overrides strings at runtime from a JSON object of `key: value` pairs. */
    @Synchronized
    fun inject(json: String) {
        val values = parse(json) ?: return
        injected.putAll(values)
        table = LinkedHashMap(table).apply { putAll(values) }
        version.intValue++
    }

    /** Returns the string for [key], or [fallback] (or the key itself) when it is missing. */
    fun text(key: String, fallback: String? = null): String {
        // Reading the state ties Compose callers to table updates.
        version.intValue
        if (!loaded) appContext?.let { load(it, preferredLocales(it)) }
        return table[key] ?: fallback ?: key.also { Log.w(TAG, "missing string: $key") }
    }

    private fun candidateTags(locale: Locale): List<String> = buildList {
        val language = locale.language.lowercase(Locale.ROOT)
        if (language.isEmpty()) return@buildList
        add(language)
        val script = locale.script
        if (script.isNotEmpty()) add("$language-$script")
        val region = locale.country
        if (region.isNotEmpty()) add("$language-${region.uppercase(Locale.ROOT)}")
    }.distinct()

    private fun readAsset(context: Context, tag: String): Map<String, String>? = try {
        context.assets.open("$DIRECTORY/$tag.json").bufferedReader().use { parse(it.readText()) }
    } catch (_: java.io.FileNotFoundException) {
        null
    } catch (error: Exception) {
        Log.w(TAG, "could not read i18n/$tag.json", error)
        null
    }

    private fun readFile(file: File): Map<String, String>? = try {
        if (file.isFile) parse(file.readText()) else null
    } catch (error: Exception) {
        Log.w(TAG, "could not read ${file.absolutePath}", error)
        null
    }

    private fun parse(json: String): Map<String, String>? = try {
        val root = JSONObject(json)
        buildMap {
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.startsWith("_")) continue // "_comment", "_language" and similar metadata
                put(key, root.optString(key))
            }
        }
    } catch (error: Exception) {
        Log.w(TAG, "invalid i18n JSON", error)
        null
    }
}

/** Looks up [key] and fills `{name}` placeholders from [args]. */
fun tr(key: String, vararg args: Pair<String, Any?>): String {
    var value = I18n.text(key)
    for ((name, arg) in args) value = value.replace("{$name}", arg.toString())
    return value
}

/** Like [tr], but uses [fallback] when the key is not in any table (for model-provided text). */
fun trOr(key: String, fallback: String): String = I18n.text(key, fallback)
