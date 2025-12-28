package com.void.block.identity.events

import com.void.slate.event.Event

/**
 * Events emitted by the Identity block.
 * Other blocks can observe these without knowing about Identity internals.
 */

/**
 * Emitted when a new identity is created for the first time.
 */
data class IdentityCreated(
    val identityFormatted: String,
    override val timestamp: Long = System.currentTimeMillis()
) : Event

/**
 * Emitted when the user regenerates their identity.
 */
data class IdentityRegenerated(
    val oldIdentity: String,
    val newIdentity: String,
    override val timestamp: Long = System.currentTimeMillis()
) : Event

/**
 * Emitted when identity is loaded from storage.
 */
data class IdentityLoaded(
    val identityFormatted: String,
    override val timestamp: Long = System.currentTimeMillis()
) : Event
