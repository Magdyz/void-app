package com.void.server.security

import com.void.server.config.VoidConfig
import mu.KotlinLogging
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Signature verification service.
 * Verifies that requests are signed by the claimed identity key.
 */
object SignatureVerification {
    private val logger = KotlinLogging.logger {}

    init {
        // Register BouncyCastle provider for cryptographic operations
        Security.addProvider(BouncyCastleProvider())
    }

    /**
     * Verify a signature using the account's public key.
     *
     * @param accountId Public key in Base64 format (or 3-word identity derived from it)
     * @param message Original message that was signed (typically timestamp)
     * @param signature Signature in Base64 format
     * @return true if signature is valid, false otherwise
     */
    fun verifySignature(accountId: String, message: String, signature: String): Boolean {
        return try {
            // Decode the public key from Base64
            val publicKeyBytes = Base64.getDecoder().decode(accountId)
            val keySpec = X509EncodedKeySpec(publicKeyBytes)
            val keyFactory = KeyFactory.getInstance("Ed25519", "BC")
            val publicKey = keyFactory.generatePublic(keySpec)

            // Decode the signature from Base64
            val signatureBytes = Base64.getDecoder().decode(signature)

            // Verify the signature
            val verifier = Signature.getInstance("Ed25519", "BC")
            verifier.initVerify(publicKey)
            verifier.update(message.toByteArray())
            val isValid = verifier.verify(signatureBytes)

            if (!isValid) {
                logger.warn { "Signature verification failed for account" }
            }

            isValid
        } catch (e: Exception) {
            logger.error(e) { "Error during signature verification" }
            false
        }
    }

    /**
     * Verify a timestamped request to prevent replay attacks.
     *
     * @param timestamp Request timestamp
     * @param signature Signature of the timestamp
     * @param accountId Account's public key
     * @return true if signature is valid and timestamp is recent
     */
    fun verifyTimestampedRequest(timestamp: Long, signature: String, accountId: String): Boolean {
        // Check if timestamp is within acceptable window
        val currentTime = System.currentTimeMillis()
        val timeDiff = Math.abs(currentTime - timestamp)

        if (timeDiff > VoidConfig.Security.signatureTimeoutMs) {
            logger.warn { "Request timestamp too old or in future: ${timeDiff}ms" }
            return false
        }

        // Verify signature on the timestamp
        return verifySignature(accountId, timestamp.toString(), signature)
    }

    /**
     * Add random jitter to prevent timing attacks.
     * Delays the response by a random amount between configured min/max.
     */
    suspend fun applyTimingJitter() {
        val jitterMs = (VoidConfig.Security.jitterMinMs..VoidConfig.Security.jitterMaxMs).random()
        kotlinx.coroutines.delay(jitterMs)
    }
}

/**
 * Exception thrown when signature verification fails.
 */
class SignatureVerificationException(message: String) : Exception(message)
