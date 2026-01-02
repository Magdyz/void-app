package app.voidapp.block.constellation.events

import com.void.slate.event.Event

/**
 * Events emitted by ConstellationBlock for cross-block communication.
 */
sealed class ConstellationEvent : Event {
    data class SetupCompleted(override val timestamp: Long) : ConstellationEvent()
    data class UnlockSuccessful(override val timestamp: Long) : ConstellationEvent()
    data class UnlockFailed(val attemptsRemaining: Int) : ConstellationEvent()
    object LockedOut : ConstellationEvent()
    object PanicWipeTriggered : ConstellationEvent()
    data class RecoveryInitiated(override val timestamp: Long) : ConstellationEvent()
}
