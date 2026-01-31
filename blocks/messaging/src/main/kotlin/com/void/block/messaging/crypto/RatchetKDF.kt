package com.void.block.messaging.crypto

import com.void.slate.crypto.CryptoProvider

/**
 * Key Derivation Functions for Double Ratchet.
 *
 * Implements the KDF functions from the Signal Protocol specification:
 * - KDF_RK: Root key derivation (from DH output)
 * - KDF_CK: Chain key derivation (symmetric ratchet)
 * - Derives encryption and MAC keys from message key
 */
class RatchetKDF(
    private val crypto: CryptoProvider
) {
    /**
     * KDF_RK: Derive new root key and chain key from DH output.
     *
     * This is called during the DH ratchet step (when we receive a new DH public key).
     * It combines the current root key with the new DH shared secret to derive:
     * - New root key (for next ratchet)
     * - New chain key (for symmetric ratchet)
     *
     * @param rootKey Current root key (32 bytes)
     * @param dhOutput DH shared secret from X25519 ECDH (32 bytes)
     * @return Pair of (newRootKey, newChainKey)
     */
    suspend fun deriveRootKey(
        rootKey: ByteArray,
        dhOutput: ByteArray
    ): Pair<ByteArray, ByteArray> {
        require(rootKey.size == 32) { "Root key must be 32 bytes" }
        require(dhOutput.size == 32) { "DH output must be 32 bytes" }

        // Combine root key and DH output for domain separation
        val combined = rootKey + dhOutput

        // Derive new root key and chain key using HKDF with different domains
        val newRootKey = crypto.derive(combined, "ratchet-root")
        val newChainKey = crypto.derive(combined, "ratchet-chain")

        return Pair(newRootKey, newChainKey)
    }

    /**
     * KDF_CK: Derive new chain key and message key from chain key.
     *
     * This is called for EVERY message in the symmetric ratchet.
     * It derives:
     * - New chain key (for next message in chain)
     * - Message key (for encrypting/decrypting THIS message)
     *
     * @param chainKey Current chain key (32 bytes)
     * @return Pair of (newChainKey, messageKey)
     */
    suspend fun deriveChainKey(
        chainKey: ByteArray
    ): Pair<ByteArray, ByteArray> {
        require(chainKey.size == 32) { "Chain key must be 32 bytes" }

        // Derive new chain key and message key using HKDF
        val newChainKey = crypto.derive(chainKey, "chain-key")
        val messageKey = crypto.derive(chainKey, "message-key")

        return Pair(newChainKey, messageKey)
    }

    /**
     * Derive encryption and MAC keys from message key.
     *
     * Each message key is used to derive:
     * - Encryption key (for AES-GCM)
     * - MAC key (for HMAC-SHA256)
     *
     * @param messageKey Message key from KDF_CK (32 bytes)
     * @return Pair of (encryptionKey, macKey)
     */
    suspend fun deriveMessageKeys(
        messageKey: ByteArray
    ): Pair<ByteArray, ByteArray> {
        require(messageKey.size == 32) { "Message key must be 32 bytes" }

        // Derive encryption and MAC keys using HKDF
        val encryptionKey = crypto.derive(messageKey, "encrypt")
        val macKey = crypto.derive(messageKey, "mac")

        return Pair(encryptionKey, macKey)
    }
}
