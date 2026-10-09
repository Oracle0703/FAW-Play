package com.shilapi.xcertplay.media

/** Stores restoration responsibility before changing the persistent OEM setting. */
internal class NavigationMixingOwnership(
    private val read: () -> Boolean?,
    private var apply: (Boolean) -> Unit,
    private val pending: () -> Boolean,
    private val record: (Boolean) -> Unit,
) {
    private var externallyChanged = false
    private var wasActive = false

    fun withApplier(next: (Boolean) -> Unit): NavigationMixingOwnership { apply = next; return this }

    fun reconcile(active: Boolean) {
        if (!active) externallyChanged = false
        if (active && !wasActive) externallyChanged = false
        wasActive = active
        val current = read() ?: return
        if (pending()) {
            if (current) {
                // An external change wins; never replay our old value over it.
                record(false)
                externallyChanged = active
            } else if (!active) {
                apply(true)
                if (read() == true) record(false)
            }
            return
        }
        if (!active || !current || externallyChanged) return
        record(true)
        apply(false)
    }

    fun observedExternalChange() {
        if (pending() && read() == true) {
            record(false)
            externallyChanged = wasActive
        }
    }
}
