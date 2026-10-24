package com.shilapi.xcertplay

import android.content.Context
import android.net.Uri
import com.shilapi.xcertplay.vehicle.profile.*
import java.io.ByteArrayOutputStream

/**
 * Files contain data only. A validated draft becomes active only on Save & Reconnect, which also
 * remembers which profile was chosen, so the next launch reopens the same one.
 */
class VehicleProfileRepository(private val context: Context) {
    /** A profile's text and where it came from: [BUNDLED_PREFIX] + asset file, or [IMPORTED]. */
    data class Selection(val text: String, val source: String, val message: String? = null)

    companion object {
        /** Head units without a document picker import from here; adb can push into this folder. */
        const val EXTERNAL_IMPORT_FILE_NAME = "vehicle-profile.json"
        const val BUNDLED_PREFIX = "bundled:"
        const val IMPORTED = "imported"
        const val DEFAULT_BUNDLED = "standard-android.json"
        private const val MAX_PROFILE_BYTES = 1_048_576

        /**
         * The remembered profile if it still parses, otherwise the default with the reason. A saved
         * imported profile from before selections were remembered counts as imported.
         */
        fun resolve(saved: String?, bundled: (String) -> String?, imported: () -> String?): Selection {
            fun valid(text: String?) = text?.takeIf { runCatching { VehicleProfileParser.parse(it) }.isSuccess }
            val source = saved ?: IMPORTED.takeIf { imported() != null }
            val text = when {
                source == null -> null
                source.startsWith(BUNDLED_PREFIX) -> valid(bundled(source.removePrefix(BUNDLED_PREFIX)))
                else -> valid(imported())
            }
            if (source != null && text != null) return Selection(text, source)
            val fallback = Selection(bundled(DEFAULT_BUNDLED) ?: error("Missing bundled $DEFAULT_BUNDLED"), BUNDLED_PREFIX + DEFAULT_BUNDLED)
            val name = source?.removePrefix(BUNDLED_PREFIX) ?: return fallback
            return fallback.copy(message = "The saved profile ($name) is missing or no longer valid for this version; using $DEFAULT_BUNDLED.")
        }
    }
    private val active = java.io.File(context.filesDir,"vehicle-profile-active.json")
    fun load(): Selection = resolve(AirPlayPersistence.loadVehicleProfileSelection(context), ::bundled) {
        runCatching { active.readText() }.getOrNull()
    }
    fun import(uri: Uri): String {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            val result=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) { val n=input.read(buffer);if(n<0) break
                require(result.size()+n<=MAX_PROFILE_BYTES){"Profile exceeds 1 MiB"};result.write(buffer,0,n)
            };result.toByteArray().toString(Charsets.UTF_8)
        } ?: error("Cannot read selected document")
        VehicleProfileParser.parse(text)
        return text
    }
    /** Used when the head unit has no document picker at all. */
    fun externalImportFile(): java.io.File = java.io.File(
        context.getExternalFilesDir(null) ?: java.io.File(context.filesDir, "shared"), EXTERNAL_IMPORT_FILE_NAME)
    fun importFile(file: java.io.File): String {
        require(file.isFile) { "No file at ${file.absolutePath}" }
        require(file.length() <= MAX_PROFILE_BYTES) { "Profile exceeds 1 MiB" }
        val text = file.readText()
        VehicleProfileParser.parse(text)
        return text
    }
    /** Bundled profiles (asset file to name) in display order, so a head unit can switch without a file picker. */
    fun bundledProfiles(): List<Pair<String,String>> = runCatching {
        (context.assets.list("vehicle-profiles") ?: emptyArray())
            .filter { it.endsWith(".json") }.sorted().mapNotNull { name ->
                val text = bundled(name) ?: return@mapNotNull null
                val profile = runCatching { VehicleProfileParser.parse(text) }.getOrNull() ?: return@mapNotNull null
                name to profile.name
            }
    }.getOrDefault(emptyList())
    fun bundled(fileName: String): String? =
        runCatching { context.assets.open("vehicle-profiles/$fileName").bufferedReader().use { it.readText() } }.getOrNull()
    /** A bundled selection is kept by name, so an app update ships its fixes to that profile. */
    fun save(selection: Selection) {
        VehicleProfileParser.parse(selection.text)
        if (selection.source == IMPORTED) {
            val file=android.util.AtomicFile(active)
            val output=file.startWrite()
            try {output.write(selection.text.toByteArray(Charsets.UTF_8));file.finishWrite(output)} catch(e:Exception){file.failWrite(output);throw e}
        }
        AirPlayPersistence.saveVehicleProfileSelection(context, selection.source)
    }
}
