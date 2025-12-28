package com.void.slate.event

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlin.reflect.KClass

/**
 * Base interface for all events in the system.
 * Events are the ONLY way blocks communicate with each other.
 */
interface Event {
    /**
     * Unique identifier for this event instance.
     */
    val eventId: String
        get() = "${this::class.simpleName}-${System.currentTimeMillis()}"
    
    /**
     * Timestamp when this event was created.
     */
    val timestamp: Long
        get() = System.currentTimeMillis()
}

/**
 * The EventBus enables decoupled communication between blocks.
 * 
 * Blocks emit events without knowing who will receive them.
 * Blocks observe events without knowing who sent them.
 * 
 * This is the "studs" in the lego analogy - how pieces connect.
 */
interface EventBus {
    /**
     * Emit an event to all observers.
     */
    suspend fun emit(event: Event)
    
    /**
     * Observe all events.
     */
    fun observe(): Flow<Event>
    
    /**
     * Observe events of a specific type.
     */
    fun <T : Event> observe(type: KClass<T>): Flow<T>
}

/**
 * In-memory implementation of the EventBus.
 * Uses SharedFlow for hot event streaming.
 */
class InMemoryEventBus : EventBus {
    
    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 64
    )
    
    override suspend fun emit(event: Event) {
        _events.emit(event)
    }
    
    override fun observe(): Flow<Event> = _events.asSharedFlow()
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : Event> observe(type: KClass<T>): Flow<T> {
        return _events.asSharedFlow().filterIsInstance(type)
    }
}

/**
 * Extension to observe events inline.
 */
inline fun <reified T : Event> EventBus.observe(): Flow<T> = observe(T::class)

/**
 * Logged event bus wrapper for debugging.
 */
class LoggingEventBus(
    private val delegate: EventBus,
    private val logger: (String) -> Unit = ::println
) : EventBus by delegate {
    
    override suspend fun emit(event: Event) {
        logger("EventBus: Emitting ${event::class.simpleName} [${event.eventId}]")
        delegate.emit(event)
    }
}

// ═══════════════════════════════════════════════════════════════════
// Common Events (used across blocks)
// ═══════════════════════════════════════════════════════════════════

/**
 * Emitted when the app starts.
 */
data object AppStarted : Event

/**
 * Emitted when the app is going to background.
 */
data object AppBackgrounded : Event

/**
 * Emitted when the app returns to foreground.
 */
data object AppForegrounded : Event

/**
 * Emitted when user authentication state changes.
 */
sealed class AuthEvent : Event {
    data object Authenticated : AuthEvent()
    data object Unauthenticated : AuthEvent()
    data object Locked : AuthEvent()
}
