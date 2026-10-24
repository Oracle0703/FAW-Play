package com.shilapi.xcertplay.airplay

/** Session display negotiation and UI transitions, flushed after the incoming request ACK. */
internal class AirPlayViewAreaControl(
    private val config: AirPlayConfig,
    private val send: (Map<String, Any?>) -> Boolean,
) {
    // Production delegates alternate UI to the existing stream-token/pause-aware cluster owner.
    var preferredAlternate: () -> String? = { config.cluster?.initialUrl }
    var selectAlternate: (String) -> Boolean = { false }
    var onDiagnostic: (String) -> Unit = {}
    private var enabled: Set<String>? = null
    private var altUrls = emptyList<String>()
    private val selected = linkedMapOf<String, String>()
    private val previous = linkedMapOf<String, String>()
    private var alternateReady = false
    private val pending = linkedMapOf<String, Map<String, Any?>>()
    private var pendingResponses = 0

    @Synchronized fun negotiate(proposed: List<*>?, eventAvailable: Boolean): List<String> {
        val accepted = setupEnabledFeatures(config, proposed, eventAvailable)
        enabled = accepted.toSet()
        pending.clear()
        if ("uiContext" in accepted && "altScreen" in accepted) chooseAlternate()
        return accepted
    }

    @Synchronized fun acceptsAlternate(): Boolean =
        config.cluster != null && (enabled == null || "altScreen" in enabled.orEmpty())

    @Synchronized fun info(request: Map<String, Any?>): Map<String, Any?> {
        if ("altScreenURLs" in request) {
            altUrls = strings(request["altScreenURLs"])
            onDiagnostic("Cluster UI capabilities=${altUrls.map(::describeAlternate).distinct()} " +
                "preferred=${describeAlternate(preferredAlternate())}")
        }
        if ("uiContext" in enabled.orEmpty()) chooseAlternate()
        val cluster = config.cluster?.let { it.copy(initialUrl = selected[AirPlayInfoPlist.ALT_UUID] ?: it.initialUrl) }
        val main = config.main.copy(initialUrl = selected[AirPlayInfoPlist.MAIN_UUID] ?: config.main.initialUrl)
        val info = AirPlayInfoPlist.build(config.copy(main = main, cluster = cluster), enabled).toMutableMap()
        if (config.receiverManagedClusterUi && cluster != null &&
            (enabled == null || "uiContext" in enabled.orEmpty())) {
            info["uiContextNowOnDisplayURLs"] = listOfNotNull(main.initialUrl, cluster.initialUrl).distinct()
            info["uiContextLastOnDisplayURLs"] = previous.values.distinct()
        }
        return info
    }

    @Synchronized fun initialAlternateUrl(): String? = selected[AirPlayInfoPlist.ALT_UUID] ?: config.cluster?.initialUrl

    @Synchronized fun screenReady(type: Int) {
        if (type != 111) return
        alternateReady = true
        if ("uiContext" in enabled.orEmpty()) selected[AirPlayInfoPlist.ALT_UUID]?.let { select(AirPlayInfoPlist.ALT_UUID, it, force = true) }
    }

    @Synchronized fun screenClosed(type: Int) { if (type == 111) alternateReady = false }

    @Synchronized fun receive(type: String, params: Map<String, Any?>) {
        if (type == "suggestUI" || type == "changeUIContext") {
            if ("uiContext" !in enabled.orEmpty()) return
            val offered = if (type == "suggestUI") strings(params["urls"]) else strings(listOf(params["url"]))
            val explicit = params["uuid"] ?: params["displayUUID"]
            val alternate = explicit == AirPlayInfoPlist.ALT_UUID ||
                (explicit == null && offered.any { alternateUrlOffered(it, altUrls) })
            val uuid = explicit ?: if (alternate) AirPlayInfoPlist.ALT_UUID else AirPlayInfoPlist.MAIN_UUID
            require(uuid == AirPlayInfoPlist.MAIN_UUID || (uuid == AirPlayInfoPlist.ALT_UUID && acceptsAlternate())) {
                "Unknown UI display"
            }
            val url = if (alternate) {
                // Keep a retained vehicle content choice when it is one of the offered URLs.
                preferredAlternate()?.takeIf { alternateUrlOffered(it, offered) &&
                    (altUrls.isEmpty() || alternateUrlOffered(it, altUrls)) }
                    ?: offered.firstOrNull { altUrls.isEmpty() || alternateUrlOffered(it, altUrls) }
            } else offered.firstOrNull()
            url?.let { select(uuid as String, it) }
            return
        }
        if (type != "requestViewArea") return
        if (enabled != null && "viewAreas" !in enabled.orEmpty()) return
        val uuid = params["displayUUID"] as? String
        require(uuid == AirPlayInfoPlist.MAIN_UUID || (uuid == AirPlayInfoPlist.ALT_UUID && acceptsAlternate())) {
            "Unknown view-area display"
        }
        val view = params["viewArea"]
        val index = (view as? Number)?.toLong() ?: ((view as? Map<*, *>)?.get("viewAreaIndex") as? Number)?.toLong()
        require(index == 0L) { "Unknown view area" }
        pending["area:$uuid"] = linkedMapOf("type" to "updateViewArea", "params" to linkedMapOf(
            "uuid" to uuid, "viewAreaIndex" to 0, "adjacentViewAreas" to emptyList<Int>(),
            "animationDurationMillis" to 0,
        ))
    }

    @Synchronized fun responseStarted() { pendingResponses++ }

    @Synchronized fun responseSent() {
        check(pendingResponses > 0)
        pendingResponses--
        flush()
    }

    /** A retired event peer must release its barrier without claiming that its ACK was written. */
    @Synchronized fun responseAborted() {
        check(pendingResponses > 0)
        pendingResponses--
    }

    @Synchronized fun flush() {
        if (pendingResponses != 0) return
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val (key, command) = iterator.next()
            if (key == AirPlayInfoPlist.ALT_UUID) {
                if (!alternateReady) continue
                val url = (command["params"] as Map<*, *>)["url"] as String
                if (!selectAlternate(url)) return
            } else if (!send(command)) return
            iterator.remove()
        }
    }

    private fun chooseAlternate() {
        val url = preferredAlternate()?.takeIf { altUrls.isEmpty() || alternateUrlOffered(it, altUrls) }
            ?: config.cluster?.initialUrl?.takeIf { alternateUrlOffered(it, altUrls) } ?: altUrls.firstOrNull()
        url?.let { select(AirPlayInfoPlist.ALT_UUID, it) }
    }

    private fun alternateUrlOffered(url: String, offered: List<String>): Boolean =
        url in offered || (alternateOptionEndpoints[url]?.let { it in offered } == true)

    private fun describeAlternate(url: String?): String = when (url) {
        CarPlayClusterDisplay.Content.MAP.url -> "map+ETA"
        CarPlayClusterDisplay.Content.INSTRUMENTS.url -> "instruments+ETA"
        CarPlayClusterDisplay.MAP_URL -> "map"
        CarPlayClusterDisplay.INSTRUMENTS_URL -> "instruments"
        CarPlayClusterDisplay.Content.TURN_CARD.url -> "turn-card"
        null -> "none"
        else -> "other"
    }

    private fun select(uuid: String, url: String, force: Boolean = false) {
        if (!force && selected[uuid] == url) return
        if (selected[uuid] != url) selected[uuid]?.let { previous[uuid] = it }
        selected[uuid] = url
        if (uuid == AirPlayInfoPlist.ALT_UUID) onDiagnostic("Cluster UI selection " +
            "preferred=${describeAlternate(preferredAlternate())} selected=${describeAlternate(url)} pending=true")
        pending[uuid] = linkedMapOf("type" to "showUI", "params" to linkedMapOf("uuid" to uuid, "url" to url))
    }

    private fun strings(value: Any?): List<String> = (value as? List<*>).orEmpty()
        .filterIsInstance<String>().filter { it.isNotBlank() }.distinct()

    private companion object {
        // Only the two exact ETA URLs may match a bare native endpoint.
        val alternateOptionEndpoints: Map<String, String> = mapOf(
            CarPlayClusterDisplay.Content.MAP.url to CarPlayClusterDisplay.MAP_URL,
            CarPlayClusterDisplay.Content.INSTRUMENTS.url to CarPlayClusterDisplay.INSTRUMENTS_URL,
        )
    }
}
