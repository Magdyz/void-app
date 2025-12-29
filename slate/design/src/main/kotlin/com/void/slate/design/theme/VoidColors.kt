package com.void.slate.design.theme

import androidx.compose.ui.graphics.Color

/**
 * VOID Color Palette
 *
 * Privacy-focused dark theme with high contrast and minimal distraction.
 * Color palette emphasizes security, privacy, and clarity.
 */
object VoidColors {

    // ═══════════════════════════════════════════════════════════════════
    // Primary - Deep Purple (trust, security, privacy)
    // ═══════════════════════════════════════════════════════════════════
    val Primary = Color(0xFF9C27B0)          // Deep Purple 500
    val PrimaryVariant = Color(0xFF6A1B9A)   // Deep Purple 800
    val PrimaryLight = Color(0xFFBA68C8)     // Deep Purple 300
    val OnPrimary = Color(0xFFFFFFFF)        // White text on primary

    // ═══════════════════════════════════════════════════════════════════
    // Secondary - Dark Teal (encrypted, secure)
    // ═══════════════════════════════════════════════════════════════════
    val Secondary = Color(0xFF00897B)        // Teal 600
    val SecondaryVariant = Color(0xFF00695C) // Teal 800
    val SecondaryLight = Color(0xFF4DB6AC)   // Teal 300
    val OnSecondary = Color(0xFFFFFFFF)      // White text on secondary

    // ═══════════════════════════════════════════════════════════════════
    // Background - True Black for OLED (privacy, battery saving)
    // ═══════════════════════════════════════════════════════════════════
    val Background = Color(0xFF000000)       // Pure black for OLED
    val BackgroundElevated = Color(0xFF121212) // Slightly elevated
    val OnBackground = Color(0xFFE0E0E0)     // Light gray text

    // ═══════════════════════════════════════════════════════════════════
    // Surface - Cards, dialogs, sheets
    // ═══════════════════════════════════════════════════════════════════
    val Surface = Color(0xFF1E1E1E)          // Dark gray
    val SurfaceVariant = Color(0xFF2C2C2C)   // Slightly lighter
    val OnSurface = Color(0xFFE0E0E0)        // Light gray text
    val OnSurfaceVariant = Color(0xFFB0B0B0) // Dimmed text

    // ═══════════════════════════════════════════════════════════════════
    // Outlines and Dividers
    // ═══════════════════════════════════════════════════════════════════
    val Outline = Color(0xFF3E3E3E)          // Subtle borders
    val OutlineVariant = Color(0xFF2C2C2C)   // Even more subtle

    // ═══════════════════════════════════════════════════════════════════
    // Status Colors
    // ═══════════════════════════════════════════════════════════════════
    val Error = Color(0xFFCF6679)            // Soft red (less alarming)
    val OnError = Color(0xFF000000)          // Black text on error
    val ErrorContainer = Color(0xFF4A1F1F)   // Dark red background
    val OnErrorContainer = Color(0xFFFFB4AB) // Light red text

    val Success = Color(0xFF66BB6A)          // Green 400
    val OnSuccess = Color(0xFF000000)        // Black text on success
    val SuccessContainer = Color(0xFF1B3A1C) // Dark green background
    val OnSuccessContainer = Color(0xFFA5D6A7) // Light green text

    val Warning = Color(0xFFFFB74D)          // Orange 300
    val OnWarning = Color(0xFF000000)        // Black text on warning
    val WarningContainer = Color(0xFF4A3A1F) // Dark orange background
    val OnWarningContainer = Color(0xFFFFCC80) // Light orange text

    // ═══════════════════════════════════════════════════════════════════
    // Special - Message expiry indicators
    // ═══════════════════════════════════════════════════════════════════
    val GhostMode = Color(0xFF9575CD)        // Purple - 30s ephemeral
    val ShadowMode = Color(0xFF7986CB)       // Indigo - 24h
    val MemoryMode = Color(0xFF64B5F6)       // Blue - 7d
    val ArchiveMode = Color(0xFF81C784)      // Green - permanent

    // ═══════════════════════════════════════════════════════════════════
    // Identity Display - Monospace friendly
    // ═══════════════════════════════════════════════════════════════════
    val IdentityPrimary = Primary            // Highlighted identity
    val IdentitySecondary = Color(0xFF757575) // Dimmed identity
    val IdentitySeparator = Color(0xFF424242) // Dots between words

    // ═══════════════════════════════════════════════════════════════════
    // Rhythm Pad - No visual feedback for security
    // ═══════════════════════════════════════════════════════════════════
    val RhythmPadBackground = Surface        // Neutral surface
    val RhythmPadHint = Color(0xFF424242)    // Very subtle hint text

    // ═══════════════════════════════════════════════════════════════════
    // Scrim - For modals and overlays
    // ═══════════════════════════════════════════════════════════════════
    val Scrim = Color(0x99000000)            // 60% black overlay
}
