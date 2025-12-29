package com.void.slate.design.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * VOID Typography System
 *
 * Clean, readable typography optimized for:
 * - Dark theme readability
 * - 3-word identity display (monospace)
 * - Message content clarity
 * - Large tap targets for rhythm input
 */
object VoidTypography {

    /**
     * Default font family (System)
     * Using system font for maximum compatibility and readability
     */
    private val DefaultFontFamily = FontFamily.Default

    /**
     * Monospace font family for identity display
     * Ensures consistent spacing for 3-word IDs like "ghost.paper.forty"
     */
    val MonoFontFamily = FontFamily.Monospace

    /**
     * Material 3 Typography scale
     */
    val Typography = Typography(
        // ═══════════════════════════════════════════════════════════════════
        // Display - Large headlines (onboarding, empty states)
        // ═══════════════════════════════════════════════════════════════════
        displayLarge = TextStyle(
            fontFamily = DefaultFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 57.sp,
            lineHeight = 64.sp,
            letterSpacing = (-0.25).sp
        ),
        displayMedium = TextStyle(
            fontFamily = DefaultFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 45.sp,
            lineHeight = 52.sp,
            letterSpacing = 0.sp
        ),
        displaySmall = TextStyle(
            fontFamily = DefaultFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 36.sp,
            lineHeight = 44.sp,
            letterSpacing = 0.sp
        ),

        // ═══════════════════════════════════════════════════════════════════
        // Headline - Screen titles, section headers
        // ═══════════════════════════════════════════════════════════════════
        headlineLarge = TextStyle(
            fontFamily = DefaultFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            letterSpacing = 0.sp
        ),
        headlineMedium = TextStyle(
            fontFamily = DefaultFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            lineHeight = 36.sp,
            letterSpacing = 0.sp
        ),
        headlineSmall = TextStyle(
            fontFamily = DefaultFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            letterSpacing = 0.sp
        ),

        // ═══════════════════════════════════════════════════════════════════
        // Title - Card titles, dialog headers
        // ═══════════════════════════════════════════════════════════════════
        titleLarge = TextStyle(
            fontFamily = DefaultFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.sp
        ),
        titleMedium = TextStyle(
            fontFamily = DefaultFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.15.sp
        ),
        titleSmall = TextStyle(
            fontFamily = DefaultFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp
        ),

        // ═══════════════════════════════════════════════════════════════════
        // Body - Message text, descriptions, most content
        // ═══════════════════════════════════════════════════════════════════
        bodyLarge = TextStyle(
            fontFamily = DefaultFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.5.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = DefaultFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.25.sp
        ),
        bodySmall = TextStyle(
            fontFamily = DefaultFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.4.sp
        ),

        // ═══════════════════════════════════════════════════════════════════
        // Label - Buttons, tabs, chips
        // ═══════════════════════════════════════════════════════════════════
        labelLarge = TextStyle(
            fontFamily = DefaultFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp
        ),
        labelMedium = TextStyle(
            fontFamily = DefaultFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp
        ),
        labelSmall = TextStyle(
            fontFamily = DefaultFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp
        )
    )

    /**
     * Custom text styles for VOID-specific use cases
     */

    /**
     * Identity display style - Monospace for consistent 3-word layout
     * Usage: "ghost.paper.forty"
     */
    val Identity = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    )

    /**
     * Identity large - For prominent display (onboarding, settings)
     */
    val IdentityLarge = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    )

    /**
     * Identity small - For compact display (message headers)
     */
    val IdentitySmall = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    )

    /**
     * Recovery phrase word style - Clear, readable
     */
    val RecoveryWord = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    )

    /**
     * Rhythm instruction text - Calm, not distracting
     */
    val RhythmHint = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )

    /**
     * Message bubble text - Optimized for readability
     */
    val MessageText = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.25.sp
    )

    /**
     * Timestamp text - Small, subtle
     */
    val Timestamp = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    )
}
