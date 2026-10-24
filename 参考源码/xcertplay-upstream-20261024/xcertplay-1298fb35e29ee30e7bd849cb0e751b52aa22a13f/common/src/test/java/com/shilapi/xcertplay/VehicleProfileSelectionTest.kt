package com.shilapi.xcertplay

import com.shilapi.xcertplay.VehicleProfileRepository.Companion.BUNDLED_PREFIX
import com.shilapi.xcertplay.VehicleProfileRepository.Companion.DEFAULT_BUNDLED
import com.shilapi.xcertplay.VehicleProfileRepository.Companion.IMPORTED
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VehicleProfileSelectionTest {
    private val assets = File("../shared/src/main/assets/vehicle-profiles")
    private fun bundled(name: String) = File(assets, name).takeIf { it.isFile }?.readText()
    private val imported = bundled(DEFAULT_BUNDLED)!!.replace("\"Standard Android Automotive\"", "\"My car\"")

    @Test fun firstLaunchUsesTheDefaultAndAnOlderImportIsKept() {
        val first = VehicleProfileRepository.resolve(null, ::bundled) { null }
        assertEquals(BUNDLED_PREFIX + DEFAULT_BUNDLED, first.source)
        assertNull(first.message)
        // Saved before selections were remembered: the active file is the user's own import.
        assertEquals(IMPORTED, VehicleProfileRepository.resolve(null, ::bundled) { imported }.source)
    }

    @Test fun aSelectionThatNoLongerParsesFallsBackVisibly() {
        val removed = VehicleProfileRepository.resolve(BUNDLED_PREFIX + "removed.json", ::bundled) { null }
        assertEquals(BUNDLED_PREFIX + DEFAULT_BUNDLED, removed.source)
        assertTrue(removed.message!!.contains("removed.json"))
        val oldSchema = VehicleProfileRepository.resolve(IMPORTED, ::bundled) { imported.replace("\"schemaVersion\": 2", "\"schemaVersion\": 1") }
        assertEquals(BUNDLED_PREFIX + DEFAULT_BUNDLED, oldSchema.source)
        assertNotNull(oldSchema.message)
    }
}
