// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.airplay.rcs.uisync

/** A local layout description. These IDs belong to Autunn, not Apple or the OEM. */
data class HongqiHs5UiLayout(
    val id: String,
    val width: Int,
    val height: Int,
    val components: Set<String>,
    val resourceVersion: String,
)

object HongqiHs5UiLayouts {
    const val RESOURCE_VERSION = "hs5-2022-3212-v1"
    const val NAVIGATION = "autunn.hs5.cluster.navigation.v1"
    const val OVERVIEW = "autunn.hs5.cluster.overview.v1"
    val ALL = listOf(
        HongqiHs5UiLayout(NAVIGATION, 1920, 720, setOf("navigation"), RESOURCE_VERSION),
        HongqiHs5UiLayout(
            OVERVIEW, 1920, 720,
            setOf("navigation", "speed", "gear", "vehicleStatus"), RESOURCE_VERSION,
        ),
    )
}

/** Actual local state, committed only after the presentation host accepts it. */
data class HongqiHs5UiState(
    val layoutId: String? = null,
    val activeComponents: Set<String> = emptySet(),
    val fadeOutOldLayout: Boolean = false,
    val focusToken: Any? = null,
    val appearanceMode: String? = null,
    val appearancePreference: String? = null,
    val appearanceOverride: Any? = null,
    val driveMode: String? = null,
    val uiConfiguration: String? = null,
    val uiConfigurationData: Any? = null,
    val driveModeToLayoutIdAssignments: Map<String, String> = emptyMap(),
    val appearanceChanging: Boolean = false,
    val layoutChanging: Boolean = false,
)

/**
 * Applies the recovered UI Sync fields to HS5's local presentation.
 *
 * No unknown layout or component is substituted. The host returns true only when it applied the
 * requested state. A false result leaves state unchanged and is not acknowledged as success.
 */
class HongqiHs5UiSyncTarget(
    layouts: Collection<HongqiHs5UiLayout> = HongqiHs5UiLayouts.ALL,
    private val onApply: (HongqiHs5UiState) -> Boolean,
    private val onInactive: () -> Unit = {},
) : CarPlayUiSyncTarget {
    private val layoutsById = layouts.associateBy { it.id }
    private var current = HongqiHs5UiState()

    init {
        require(layoutsById.size == layouts.size && layouts.isNotEmpty())
        require(layouts.all { it.id.isNotBlank() && it.width > 0 && it.height > 0 })
    }

    @Synchronized
    fun snapshot(): HongqiHs5UiState = ownedCopy(current)

    @Synchronized
    override fun reset(): Boolean = commit(HongqiHs5UiState())

    @Synchronized
    override fun apply(payload: CarPlayUiSyncCommandPayload): Boolean {
        val f = payload.fields
        val next = when (payload.command) {
            CarPlayUiSyncCommand.REQUEST_LAYOUT -> {
                if (!f.only("layout", "fadeOutOldLayout", "activeComponents")) return false
                val id = f["layout"] as? String ?: current.layoutId ?: return false
                val layout = layoutsById[id] ?: return false
                val components = if (f.containsKey("activeComponents")) {
                    val values = f["activeComponents"] as? Collection<*> ?: return false
                    if (values.any { it !is String }) return false
                    values.filterIsInstance<String>().toSet()
                } else layout.components
                if (!layout.components.containsAll(components)) return false
                current.copy(
                    layoutId = id,
                    activeComponents = components,
                    fadeOutOldLayout = f["fadeOutOldLayout"] as? Boolean ?: false,
                    layoutChanging = true,
                )
            }
            CarPlayUiSyncCommand.END_LAYOUT_CHANGE -> {
                if (f.isNotEmpty() || current.layoutId == null) return false
                current.copy(layoutChanging = false)
            }
            CarPlayUiSyncCommand.GIVE_FOCUS,
            CarPlayUiSyncCommand.REQUEST_FOCUS -> {
                if (!f.only("focusToken")) return false
                val token = f["focusToken"]
                if (token != null && token !is String && token !is Byte && token !is Short &&
                    token !is Int && token !is Long) return false
                if (token is String && token.length > 256) return false
                current.copy(focusToken = token)
            }
            CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE -> {
                if (!f.only("appearanceMode")) return false
                val mode = f.nonBlank("appearanceMode") ?: return false
                current.copy(appearanceMode = mode)
            }
            CarPlayUiSyncCommand.APPEARANCE_PREFERENCE_CHANGE -> {
                if (!f.only("preference", "override")) return false
                val preference = f.nonBlank("preference") ?: return false
                val override = f["override"] ?: return false
                if (override !is Boolean && override !is String) return false
                current.copy(appearancePreference = preference, appearanceOverride = override)
            }
            CarPlayUiSyncCommand.DRIVE_MODE_CHANGE -> {
                if (!f.only("driveMode")) return false
                val mode = f.nonBlank("driveMode") ?: return false
                val layoutId = current.driveModeToLayoutIdAssignments[mode]
                val layout = layoutId?.let(layoutsById::get)
                current.copy(
                    driveMode = mode,
                    layoutId = layout?.id ?: current.layoutId,
                    activeComponents = layout?.components ?: current.activeComponents,
                )
            }
            CarPlayUiSyncCommand.UI_CONFIGURATION_CHANGE -> {
                if (!f.only("uiConfiguration", "data")) return false
                val configuration = f.nonBlank("uiConfiguration") ?: return false
                // Own a defensive copy. Opaque bytes are passed to the host, never executed here.
                val data = f["data"]?.let { defensiveCopy(it) }
                current.copy(uiConfiguration = configuration, uiConfigurationData = data)
            }
            CarPlayUiSyncCommand.DRIVE_MODE_DYNAMIC_ASSIGNMENT_CHANGE -> {
                if (!f.only("driveModeToLayoutIdAssignments")) return false
                val values = f["driveModeToLayoutIdAssignments"] as? Map<*, *> ?: return false
                if (values.size > 32 || values.any { (k, v) ->
                        k !is String || k.isBlank() || k.length > 128 ||
                            v !is String || !layoutsById.containsKey(v)
                    }) return false
                val assignments = values.entries.associate { (k, v) -> k as String to v as String }
                current.copy(driveModeToLayoutIdAssignments = assignments)
            }
            CarPlayUiSyncCommand.APPEARANCE_CHANGE_START -> {
                if (f.isNotEmpty()) return false
                current.copy(appearanceChanging = true)
            }
            CarPlayUiSyncCommand.APPEARANCE_CHANGE_END -> {
                if (f.isNotEmpty()) return false
                current.copy(appearanceChanging = false)
            }
            // The upstream catalog names these commands but supplies no executable field schema.
            else -> return false
        }
        return commit(next)
    }

    @Synchronized
    override fun inactive() {
        current = HongqiHs5UiState()
        onInactive()
    }

    private fun commit(next: HongqiHs5UiState): Boolean {
        if (!onApply(ownedCopy(next))) return false
        current = ownedCopy(next)
        return true
    }

    private fun ownedCopy(state: HongqiHs5UiState) = state.copy(
        activeComponents = state.activeComponents.toSet(),
        driveModeToLayoutIdAssignments = state.driveModeToLayoutIdAssignments.toMap(),
        uiConfigurationData = state.uiConfigurationData?.let(::defensiveCopy),
    )

    private fun Map<String, Any?>.only(vararg allowed: String): Boolean = keys.all { it in allowed }
    private fun Map<String, Any?>.nonBlank(key: String): String? =
        (this[key] as? String)?.takeIf { it.isNotBlank() && it.length <= 128 }

    private fun defensiveCopy(value: Any): Any = when (value) {
        is ByteArray -> value.copyOf()
        is Map<*, *> -> value.entries.associate { (key, item) -> key to item?.let(::defensiveCopy) }
        is Collection<*> -> value.map { it?.let(::defensiveCopy) }
        else -> value
    }
}
