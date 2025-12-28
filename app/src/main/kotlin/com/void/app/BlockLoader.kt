package com.void.app

import com.void.block.contacts.ContactsBlock
import com.void.block.decoy.DecoyBlock
import com.void.block.identity.IdentityBlock
import com.void.block.messaging.MessagingBlock
import com.void.block.onboarding.OnboardingBlock
import com.void.block.rhythm.RhythmBlock
import com.void.slate.block.BlockManifest
import com.void.slate.block.FeatureFlags
import com.void.slate.block.isEnabled

/**
 * Discovers and loads all available blocks.
 * 
 * In a more sophisticated setup, this could use ServiceLoader
 * or compile-time code generation. For now, we manually register
 * blocks - which has the advantage of making dependencies explicit.
 * 
 * TO ADD A NEW BLOCK:
 * 1. Create the block module in blocks/
 * 2. Add it to settings.gradle.kts
 * 3. Add it to the list below
 * 
 * TO REMOVE A BLOCK:
 * 1. Comment out in settings.gradle.kts (compile-time removal)
 * 2. OR: Use feature flags (runtime removal)
 */
object BlockLoader {
    
    /**
     * Discover all available blocks.
     * Only returns blocks that are enabled.
     */
    fun discover(featureFlags: FeatureFlags = FeatureFlags.AllEnabled): List<BlockManifest> {
        return allBlocks().filter { it.isEnabled(featureFlags) }
    }
    
    /**
     * All blocks in the app.
     * Order matters for navigation priority.
     */
    private fun allBlocks(): List<BlockManifest> = listOf(
        // Core blocks (always enabled)
        OnboardingBlock(),
        IdentityBlock(),
        RhythmBlock(),
        
        // Feature blocks
        MessagingBlock(),
        ContactsBlock(),
        
        // Optional blocks
        DecoyBlock(),
    )
    
    /**
     * Get a specific block by ID.
     */
    fun getBlock(id: String): BlockManifest? {
        return allBlocks().find { it.id == id }
    }
    
    /**
     * Get all block IDs.
     */
    fun getAllBlockIds(): List<String> {
        return allBlocks().map { it.id }
    }
}

/**
 * Runtime feature flags.
 * 
 * In production, this would read from:
 * - Remote config (Firebase, etc.)
 * - Local preferences
 * - Build variants
 */
class RuntimeFeatureFlags : FeatureFlags {
    
    private val overrides = mutableMapOf<String, Boolean>()
    
    override fun isEnabled(flag: String, default: Boolean): Boolean {
        return overrides[flag] ?: default
    }
    
    /**
     * Override a flag at runtime.
     * Useful for testing or debugging.
     */
    fun setOverride(flag: String, enabled: Boolean) {
        overrides[flag] = enabled
    }
    
    /**
     * Clear all overrides.
     */
    fun clearOverrides() {
        overrides.clear()
    }
}
