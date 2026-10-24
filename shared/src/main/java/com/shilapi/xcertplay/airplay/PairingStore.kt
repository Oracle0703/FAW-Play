package com.shilapi.xcertplay.airplay

/** Store of paired controllers keyed by their long-term Ed25519 public key. */
class PairingStore(private val onSave: ((String, ByteArray) -> Unit)? = null) {
    private val entries = HashMap<String, ByteArray>()

    fun save(identifier: String, longTermPublicKey: ByteArray) {
        if (entries[identifier]?.contentEquals(longTermPublicKey) == true) return
        val copy = longTermPublicKey.copyOf()
        entries[identifier] = copy
        onSave?.invoke(identifier, copy)
    }

    /** Restoring trusted data must not write it back or publish a newly paired controller. */
    fun restore(identifier: String, longTermPublicKey: ByteArray) {
        entries[identifier] = longTermPublicKey.copyOf()
    }

    fun get(identifier: String): ByteArray? = entries[identifier]?.copyOf()

    fun clear() = entries.clear()
}
