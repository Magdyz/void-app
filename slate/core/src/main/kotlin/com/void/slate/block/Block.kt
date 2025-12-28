package com.void.slate.block

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import com.void.slate.event.Event
import com.void.slate.navigation.Navigator
import com.void.slate.navigation.Route
import org.koin.core.module.Module
import kotlin.reflect.KClass

/**
 * Annotation to mark a class as a VOID Block.
 * 
 * @param id Unique identifier for this block
 * @param flag Optional feature flag to control runtime availability
 * @param enabledByDefault Whether this block is enabled by default
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Block(
    val id: String,
    val flag: String = "",
    val enabledByDefault: Boolean = true
)

/**
 * Every block implements this manifest to declare:
 * - What routes it provides
 * - What events it emits/observes
 * - How to install its dependencies
 * - How to set up its navigation
 */
interface BlockManifest {
    
    /**
     * Unique identifier for this block.
     * Should match the @Block annotation id.
     */
    val id: String
    
    /**
     * Routes this block provides.
     * These become part of the app's navigation graph.
     */
    val routes: List<Route>
    
    /**
     * Events this block participates in.
     * Used for documentation and runtime validation.
     */
    val events: BlockEvents
        get() = BlockEvents.None
    
    /**
     * Install this block's dependencies into the DI container.
     */
    fun Module.install()
    
    /**
     * Set up this block's navigation routes.
     */
    @Composable
    fun NavGraphBuilder.routes(navigator: Navigator)
    
    /**
     * Optional initialization logic when the block is loaded.
     */
    suspend fun onLoad() {}
    
    /**
     * Optional cleanup when the block is unloaded.
     */
    suspend fun onUnload() {}
}

/**
 * Declares what events a block emits and observes.
 * This creates a clear contract for cross-block communication.
 */
data class BlockEvents(
    val emits: List<KClass<out Event>> = emptyList(),
    val observes: List<KClass<out Event>> = emptyList()
) {
    companion object {
        val None = BlockEvents()
    }
}

/**
 * Extension to get the Block annotation from a manifest.
 */
fun BlockManifest.getAnnotation(): Block? = 
    this::class.annotations.filterIsInstance<Block>().firstOrNull()

/**
 * Check if this block is enabled based on its feature flag.
 */
fun BlockManifest.isEnabled(featureFlags: FeatureFlags): Boolean {
    val annotation = getAnnotation() ?: return true
    if (annotation.flag.isEmpty()) return true
    return featureFlags.isEnabled(annotation.flag, annotation.enabledByDefault)
}

/**
 * Interface for feature flag checking.
 * Implement this to control block availability at runtime.
 */
interface FeatureFlags {
    fun isEnabled(flag: String, default: Boolean = true): Boolean
    
    companion object {
        val AllEnabled = object : FeatureFlags {
            override fun isEnabled(flag: String, default: Boolean) = true
        }
    }
}
