package com.void.block.identity.domain

import com.void.block.identity.data.IdentityRepository
import com.void.slate.crypto.CryptoProvider

/**
 * Use case for generating a new 3-word identity.
 */
class GenerateIdentity(
    private val repository: IdentityRepository,
    private val dictionary: WordDictionary,
    private val crypto: CryptoProvider
) {
    
    /**
     * Generate a new identity.
     * 
     * @param regenerate If true, generates a new identity even if one exists
     * @return The generated identity
     */
    suspend operator fun invoke(regenerate: Boolean = false): Identity {
        // Check for existing identity
        if (!regenerate) {
            repository.getIdentity()?.let { return it }
        }
        
        // Generate new cryptographic seed
        val seed = crypto.generateSeed(32)
        
        // Derive 3 word indices from seed
        val wordIndices = deriveWordIndices(seed)
        
        // Get words from dictionary
        val words = wordIndices.map { dictionary.getWord(it) }
        
        // Create identity
        val identity = Identity(
            words = words,
            seed = seed,
            createdAt = System.currentTimeMillis()
        )
        
        // Save identity
        repository.saveIdentity(identity)
        
        return identity
    }
    
    private suspend fun deriveWordIndices(seed: ByteArray): List<Int> {
        // Derive 3 indices, each in range 0..4095 (12 bits = 4096 words)
        return listOf(
            crypto.derive(seed, "word/0"),
            crypto.derive(seed, "word/1"),
            crypto.derive(seed, "word/2")
        ).map { derived ->
            // Take first 2 bytes and convert to 12-bit index
            ((derived[0].toInt() and 0xFF) shl 4) or ((derived[1].toInt() and 0xF0) shr 4)
        }
    }
}

/**
 * The user's 3-word identity.
 */
data class Identity(
    val words: List<String>,
    val seed: ByteArray,
    val createdAt: Long
) {
    /**
     * The formatted identity string (e.g., "ghost.paper.forty")
     */
    val formatted: String
        get() = words.joinToString(".")
    
    /**
     * Short form for display (e.g., "ghost.paper...")
     */
    val short: String
        get() = "${words[0]}.${words[1]}..."
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Identity) return false
        return words == other.words && seed.contentEquals(other.seed)
    }
    
    override fun hashCode(): Int {
        var result = words.hashCode()
        result = 31 * result + seed.contentHashCode()
        return result
    }
}

/**
 * Dictionary of 4096 words for identity generation.
 * Uses the BIP-39 English wordlist.
 */
class WordDictionary {
    
    // In production, this would load from resources
    // Using a subset here for demonstration
    private val words: List<String> = listOf(
        // Sample words - actual implementation has 4096
        "abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract",
        "absurd", "abuse", "access", "accident", "account", "accuse", "achieve", "acid",
        "acoustic", "acquire", "across", "act", "action", "actor", "actress", "actual",
        "adapt", "add", "addict", "address", "adjust", "admit", "adult", "advance",
        "advice", "aerobic", "affair", "afford", "afraid", "again", "age", "agent",
        "agree", "ahead", "aim", "air", "airport", "aisle", "alarm", "album",
        "alcohol", "alert", "alien", "all", "alley", "allow", "almost", "alone",
        "alpha", "already", "also", "alter", "always", "amateur", "amazing", "among",
        // ... 4000+ more words in production
        "ghost", "paper", "forty", "ocean", "mirror", "velvet", "thunder", "nine",
        "shadow", "crystal", "ember", "frost", "lunar", "solar", "void", "zero"
    ).let { sample ->
        // Pad to 4096 for demonstration
        (0 until 4096).map { i -> sample.getOrElse(i % sample.size) { "word$i" } }
    }
    
    fun getWord(index: Int): String {
        require(index in 0 until 4096) { "Index must be 0-4095" }
        return words[index]
    }
    
    fun indexOf(word: String): Int {
        return words.indexOf(word.lowercase())
    }
    
    fun isValidWord(word: String): Boolean {
        return words.contains(word.lowercase())
    }
    
    companion object {
        const val WORD_COUNT = 4096
        const val TOTAL_COMBINATIONS = 4096L * 4096 * 4096  // ~68 billion
    }
}
