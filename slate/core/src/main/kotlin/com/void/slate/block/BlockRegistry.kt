package com.void.slate.block

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central registry for all loaded blocks.
 * 
 * The BlockRegistry is responsible for:
 * - Discovering available blocks
 * - Loading/unloading blocks based on feature flags
 * - Providing access to block manifests
 * - Tracking block state
 */
class BlockRegistry(
    private val featureFlags: FeatureFlags = FeatureFlags.AllEnabled
) {
    
    private val _loadedBlocks = MutableStateFlow<Map<String, BlockManifest>>(emptyMap())
    val loadedBlocks: StateFlow<Map<String, BlockManifest>> = _loadedBlocks.asStateFlow()
    
    private val _blockStates = MutableStateFlow<Map<String, BlockState>>(emptyMap())
    val blockStates: StateFlow<Map<String, BlockState>> = _blockStates.asStateFlow()
    
    /**
     * Register and load blocks.
     * Only enabled blocks will be loaded.
     */
    suspend fun register(vararg blocks: BlockManifest) {
        blocks.forEach { block ->
            if (block.isEnabled(featureFlags)) {
                loadBlock(block)
            } else {
                updateBlockState(block.id, BlockState.Disabled)
            }
        }
    }
    
    /**
     * Get a loaded block by ID.
     */
    fun getBlock(id: String): BlockManifest? = _loadedBlocks.value[id]
    
    /**
     * Get all loaded blocks.
     */
    fun getAllBlocks(): List<BlockManifest> = _loadedBlocks.value.values.toList()
    
    /**
     * Check if a block is loaded.
     */
    fun isLoaded(id: String): Boolean = _loadedBlocks.value.containsKey(id)
    
    /**
     * Dynamically load a block at runtime.
     */
    suspend fun loadBlock(block: BlockManifest) {
        try {
            updateBlockState(block.id, BlockState.Loading)
            block.onLoad()
            _loadedBlocks.value = _loadedBlocks.value + (block.id to block)
            updateBlockState(block.id, BlockState.Loaded)
        } catch (e: Exception) {
            updateBlockState(block.id, BlockState.Error(e))
        }
    }
    
    /**
     * Dynamically unload a block at runtime.
     */
    suspend fun unloadBlock(id: String) {
        val block = _loadedBlocks.value[id] ?: return
        try {
            updateBlockState(id, BlockState.Unloading)
            block.onUnload()
            _loadedBlocks.value = _loadedBlocks.value - id
            updateBlockState(id, BlockState.Unloaded)
        } catch (e: Exception) {
            updateBlockState(id, BlockState.Error(e))
        }
    }
    
    private fun updateBlockState(id: String, state: BlockState) {
        _blockStates.value = _blockStates.value + (id to state)
    }
}

/**
 * Represents the current state of a block.
 */
sealed class BlockState {
    data object Loading : BlockState()
    data object Loaded : BlockState()
    data object Unloading : BlockState()
    data object Unloaded : BlockState()
    data object Disabled : BlockState()
    data class Error(val exception: Exception) : BlockState()
}
