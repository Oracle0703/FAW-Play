package com.shilapi.xcertplay.airplay

import java.util.UUID

/** Long-term Ed25519 identity of the CarPlay accessory, persisted by the caller. */
data class AirPlayIdentity(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val pairingId: String,
) {
    val publicKeyHex: String get() = publicKey.toHexString()

    /** Checks persisted trust inside the module that owns the cryptographic dependency. */
    fun hasValidKeyPair(): Boolean {
        if (privateKey.size != 32 || publicKey.size != 32 || pairingId.isBlank()) return false
        return runCatching {
            org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(privateKey, 0)
                .generatePublicKey().encoded.contentEquals(publicKey)
        }.getOrDefault(false)
    }

    companion object {
        fun generate(): AirPlayIdentity {
            val keyPair = AirPlayCrypto.ed25519Generate()
            return AirPlayIdentity(keyPair.privateKey, keyPair.publicKey, UUID.randomUUID().toString())
        }
    }
}
