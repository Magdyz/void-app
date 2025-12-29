package com.void.slate.crypto.wordlist

import java.security.MessageDigest

/**
 * BIP-39 mnemonic phrase generation and validation.
 * Used for recovery phrase functionality.
 *
 * Supports:
 * - 12-word phrases (128 bits entropy + 4 bits checksum = 132 bits)
 * - 24-word phrases (256 bits entropy + 8 bits checksum = 264 bits)
 *
 * Reference: https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki
 */
object BIP39 {

    /**
     * Generate mnemonic phrase from entropy.
     *
     * @param entropy 16 bytes (128 bits) for 12 words, or 32 bytes (256 bits) for 24 words
     * @return List of mnemonic words
     * @throws IllegalArgumentException if entropy is not 16 or 32 bytes
     */
    fun toMnemonic(entropy: ByteArray): List<String> {
        require(entropy.size == 16 || entropy.size == 32) {
            "Entropy must be 16 bytes (128 bits) or 32 bytes (256 bits), got ${entropy.size} bytes"
        }

        // Calculate checksum
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val checksumBits = entropy.size / 4 // 4 bits for 16 bytes, 8 bits for 32 bytes

        // Combine entropy + checksum bits
        val entropyBits = entropy.toBitString()
        val checksumBitsString = hash.toBitString().take(checksumBits)
        val combined = entropyBits + checksumBitsString

        // Split into 11-bit groups and convert to words
        val wordCount = combined.length / 11
        val words = mutableListOf<String>()

        for (i in 0 until wordCount) {
            val bits = combined.substring(i * 11, (i + 1) * 11)
            val index = bits.toInt(2)
            words.add(WordDictionary.words[index])
        }

        return words
    }

    /**
     * Convert mnemonic phrase back to entropy.
     *
     * @param mnemonic List of 12 or 24 words
     * @return Original entropy bytes
     * @throws IllegalArgumentException if mnemonic is invalid
     */
    fun toEntropy(mnemonic: List<String>): ByteArray {
        require(mnemonic.size == 12 || mnemonic.size == 24) {
            "Mnemonic must be 12 or 24 words, got ${mnemonic.size} words"
        }

        // Convert words to bit string
        val bits = mnemonic.joinToString("") { word ->
            val index = WordDictionary.indexOf(word)
            require(index >= 0) { "Invalid word: '$word'" }
            index.toString(2).padStart(11, '0')
        }

        // Extract entropy (minus checksum bits)
        val checksumBits = mnemonic.size / 3 // 4 bits for 12 words, 8 for 24
        val entropyBits = bits.length - checksumBits
        val entropyString = bits.take(entropyBits)

        // Convert to bytes
        val entropy = entropyString.chunked(8)
            .map { it.toInt(2).toByte() }
            .toByteArray()

        // Verify checksum
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val expectedChecksum = hash.toBitString().take(checksumBits)
        val actualChecksum = bits.takeLast(checksumBits)

        require(expectedChecksum == actualChecksum) {
            "Invalid checksum: mnemonic phrase is corrupted or incorrect"
        }

        return entropy
    }

    /**
     * Validate a mnemonic phrase.
     *
     * Checks:
     * - Word count is 12 or 24
     * - All words are in the BIP-39 dictionary
     * - Checksum is valid
     *
     * @param mnemonic List of words to validate
     * @return true if valid, false otherwise
     */
    fun validate(mnemonic: List<String>): Boolean {
        // Check word count
        if (mnemonic.size != 12 && mnemonic.size != 24) return false

        // Check all words are valid
        if (!mnemonic.all { WordDictionary.isValidWord(it) }) return false

        // Verify checksum by attempting conversion
        return try {
            val entropy = toEntropy(mnemonic)
            val regenerated = toMnemonic(entropy)
            // Compare normalized (lowercase) versions
            regenerated.map { it.lowercase() } == mnemonic.map { it.lowercase() }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Normalize a mnemonic phrase (trim, lowercase, handle multiple spaces).
     *
     * @param phrase Space-separated mnemonic phrase
     * @return List of normalized words
     */
    fun normalizeMnemonic(phrase: String): List<String> {
        return phrase.trim()
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
    }

    /**
     * Check if a single word is in the BIP-39 dictionary.
     */
    fun isValidWord(word: String): Boolean = WordDictionary.isValidWord(word)

    /**
     * Get suggestions for a partial word (useful for autocomplete).
     *
     * @param partial Partial word to match
     * @param maxResults Maximum number of results to return
     * @return List of matching words from dictionary
     */
    fun suggestWords(partial: String, maxResults: Int = 10): List<String> {
        val normalized = partial.lowercase()
        return WordDictionary.words
            .filter { it.startsWith(normalized) }
            .take(maxResults)
    }

    /**
     * Generate entropy of specified size.
     * This is a convenience function - in production, use a secure random source.
     *
     * @param bytes Number of bytes (16 for 12 words, 32 for 24 words)
     * @return Random entropy bytes
     */
    fun generateEntropy(bytes: Int = 16): ByteArray {
        require(bytes == 16 || bytes == 32) {
            "Entropy size must be 16 or 32 bytes"
        }
        return java.security.SecureRandom().let { random ->
            ByteArray(bytes).also { random.nextBytes(it) }
        }
    }
}

/**
 * Result of mnemonic validation with detailed error information.
 */
sealed class MnemonicValidationResult {
    object Valid : MnemonicValidationResult()

    sealed class Invalid : MnemonicValidationResult() {
        data class WrongWordCount(val count: Int) : Invalid()
        data class InvalidWords(val invalidWords: List<String>) : Invalid()
        object InvalidChecksum : Invalid()
    }
}

/**
 * Validate mnemonic with detailed error information.
 */
fun validateMnemonicDetailed(mnemonic: List<String>): MnemonicValidationResult {
    // Check word count
    if (mnemonic.size != 12 && mnemonic.size != 24) {
        return MnemonicValidationResult.Invalid.WrongWordCount(mnemonic.size)
    }

    // Check for invalid words
    val invalidWords = mnemonic.filter { !WordDictionary.isValidWord(it) }
    if (invalidWords.isNotEmpty()) {
        return MnemonicValidationResult.Invalid.InvalidWords(invalidWords)
    }

    // Verify checksum
    return try {
        val entropy = BIP39.toEntropy(mnemonic)
        val regenerated = BIP39.toMnemonic(entropy)
        if (regenerated.map { it.lowercase() } == mnemonic.map { it.lowercase() }) {
            MnemonicValidationResult.Valid
        } else {
            MnemonicValidationResult.Invalid.InvalidChecksum
        }
    } catch (e: Exception) {
        MnemonicValidationResult.Invalid.InvalidChecksum
    }
}
