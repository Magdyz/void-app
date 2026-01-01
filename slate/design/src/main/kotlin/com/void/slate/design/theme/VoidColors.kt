package com.void.slate.design.theme

import androidx.compose.ui.graphics.Color

/**
 * VOID Color Palette - 2026 Minimal Black & White Edition
 *
 * Bold, contrasty, modern theme with pure black and white.
 * Maximum contrast for clarity and focus.
 */
object VoidColors {

    // ═══════════════════════════════════════════════════════════════════
    // Primary - Pure Black (bold, modern, minimal)
    // ═══════════════════════════════════════════════════════════════════
    val Primary = Color(0xFF000000)          // Pure Black
    val PrimaryVariant = Color(0xFF000000)   // Pure Black
    val PrimaryLight = Color(0xFF1A1A1A)     // Near Black
    val OnPrimary = Color(0xFFFFFFFF)        // Pure White text

    // ═══════════════════════════════════════════════════════════════════
    // Secondary - Pure White (clean, clear)
    // ═══════════════════════════════════════════════════════════════════
    val Secondary = Color(0xFFFFFFFF)        // Pure White
    val SecondaryVariant = Color(0xFFEEEEEE) // Near White
    val SecondaryLight = Color(0xFFFFFFFF)   // Pure White
    val OnSecondary = Color(0xFF000000)      // Pure Black text

    // ═══════════════════════════════════════════════════════════════════
    // Background - Pure Black for OLED (modern, sleek)
    // ═══════════════════════════════════════════════════════════════════
    val Background = Color(0xFF000000)       // Pure Black
    val BackgroundElevated = Color(0xFF0A0A0A) // Barely elevated
    val OnBackground = Color(0xFFFFFFFF)     // Pure White text

    // ═══════════════════════════════════════════════════════════════════
    // Surface - Cards, dialogs, sheets
    // ═══════════════════════════════════════════════════════════════════
    val Surface = Color(0xFF000000)          // Pure Black
    val SurfaceVariant = Color(0xFF0D0D0D)   // Slightly lighter black
    val OnSurface = Color(0xFFFFFFFF)        // Pure White text
    val OnSurfaceVariant = Color(0xFFCCCCCC) // Light gray text

    // ═══════════════════════════════════════════════════════════════════
    // Outlines and Dividers
    // ═══════════════════════════════════════════════════════════════════
    val Outline = Color(0xFF333333)          // Dark gray borders
    val OutlineVariant = Color(0xFF1A1A1A)   // Subtle borders

    // ═══════════════════════════════════════════════════════════════════
    // Status Colors - Minimal black/white with subtle grays
    // ═══════════════════════════════════════════════════════════════════
    val Error = Color(0xFFFFFFFF)            // White for errors
    val OnError = Color(0xFF000000)          // Black text on error
    val ErrorContainer = Color(0xFF1A1A1A)   // Dark gray background
    val OnErrorContainer = Color(0xFFFFFFFF) // White text

    val Success = Color(0xFFFFFFFF)          // White for success
    val OnSuccess = Color(0xFF000000)        // Black text on success
    val SuccessContainer = Color(0xFF1A1A1A) // Dark gray background
    val OnSuccessContainer = Color(0xFFFFFFFF) // White text

    val Warning = Color(0xFFFFFFFF)          // White for warning
    val OnWarning = Color(0xFF000000)        // Black text on warning
    val WarningContainer = Color(0xFF1A1A1A) // Dark gray background
    val OnWarningContainer = Color(0xFFFFFFFF) // White text

    // ═══════════════════════════════════════════════════════════════════
    // Special - Message expiry indicators (grayscale)
    // ═══════════════════════════════════════════════════════════════════
    val GhostMode = Color(0xFFFFFFFF)        // White - 30s ephemeral
    val ShadowMode = Color(0xFFCCCCCC)       // Light gray - 24h
    val MemoryMode = Color(0xFF999999)       // Medium gray - 7d
    val ArchiveMode = Color(0xFF666666)      // Dark gray - permanent

    // ═══════════════════════════════════════════════════════════════════
    // Identity Display - Monospace friendly
    // ═══════════════════════════════════════════════════════════════════
    val IdentityPrimary = Color(0xFFFFFFFF)  // White highlighted identity
    val IdentitySecondary = Color(0xFF999999) // Gray dimmed identity
    val IdentitySeparator = Color(0xFF333333) // Dark gray dots

    // ═══════════════════════════════════════════════════════════════════
    // Rhythm Pad - No visual feedback for security
    // ═══════════════════════════════════════════════════════════════════
    val RhythmPadBackground = Surface        // Black surface
    val RhythmPadHint = Color(0xFF333333)    // Very subtle hint text

    // ═══════════════════════════════════════════════════════════════════
    // Scrim - For modals and overlays
    // ═══════════════════════════════════════════════════════════════════
    val Scrim = Color(0xCC000000)            // 80% black overlay
}
