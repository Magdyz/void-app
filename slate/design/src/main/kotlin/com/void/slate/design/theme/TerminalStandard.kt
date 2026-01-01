package com.void.slate.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Terminal Standard - "Solid State" Design System
 *
 * A minimalist, crash-proof design language using only:
 * - Standard text and basic shapes
 * - Brackets [ ] as UI elements
 * - Monospace fonts
 * - Pure black and white with grey accents
 *
 * The Rule: If it can't be done with standard text and basic shapes, we don't do it.
 */
object TerminalStandard {

    // ═══════════════════════════════════════════════════════════════════
    // Colors - The "Safe" Foundation
    // ═══════════════════════════════════════════════════════════════════

    val Background = Color(0xFF000000)      // True Black
    val Text = Color(0xFFFFFFFF)            // Pure White
    val TextSecondary = Color(0xFF888888)   // Mid-Grey (timestamps, placeholders)
    val Border = Color(0xFF333333)          // Dark Grey (brackets, borders)
    val Disabled = Color(0xFF444444)        // Darker Grey (disabled states)

    // ═══════════════════════════════════════════════════════════════════
    // Typography - Monospace Everything
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Header style - Used for screen titles like "// INBOX"
     */
    val Header = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
        color = Text
    )

    /**
     * Body style - Regular text content
     */
    val Body = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        color = Text
    )

    /**
     * Body Bold - Emphasized text (unread messages, names)
     */
    val BodyBold = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        color = Text
    )

    /**
     * Caption - Small text (timestamps, hints)
     */
    val Caption = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
        color = TextSecondary
    )

    /**
     * Button style - Text for buttons like "[ SEND ]"
     */
    val Button = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        color = Background  // Black text on white button
    )

    /**
     * Input style - Text field content
     */
    val Input = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        color = Text
    )

    /**
     * Identity style - 3-word identities
     */
    val Identity = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        color = Text
    )

    /**
     * Monogram style - Single letter in brackets like "[P]"
     */
    val Monogram = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        color = Text
    )

    // ═══════════════════════════════════════════════════════════════════
    // UI Element Markers - Text-based UI indicators
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Bracket markers for UI elements
     */
    const val BRACKET_OPEN = "["
    const val BRACKET_CLOSE = "]"

    /**
     * Arrow markers for message direction
     */
    const val ARROW_RECEIVED = ">"
    const val ARROW_SENT = "<"

    /**
     * Comment marker for headers
     */
    const val COMMENT = "//"

    /**
     * Plus marker for new actions
     */
    const val PLUS = "+"

    /**
     * Underline marker for inputs
     */
    const val INPUT_FILL = "___________"

    // ═══════════════════════════════════════════════════════════════════
    // Helper Functions - UI Element Generators
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Creates a bracketed monogram from a name
     * Example: "peace.dry.dwarf" -> "[P]"
     */
    fun monogram(name: String): String {
        val firstLetter = name.firstOrNull()?.uppercase() ?: "?"
        return "$BRACKET_OPEN$firstLetter$BRACKET_CLOSE"
    }

    /**
     * Creates a bracketed label
     * Example: "NEW" -> "[ NEW ]"
     */
    fun bracketLabel(text: String): String {
        return "$BRACKET_OPEN $text $BRACKET_CLOSE"
    }

    /**
     * Creates a header with comment prefix
     * Example: "INBOX" -> "// INBOX"
     */
    fun header(text: String): String {
        return "$COMMENT $text"
    }

    /**
     * Creates a message with direction arrow
     * Example: received("hello") -> "> hello"
     *          sent("hi") -> "hi <"
     */
    fun receivedMessage(text: String): String {
        return "$ARROW_RECEIVED $text"
    }

    fun sentMessage(text: String): String {
        return "$text $ARROW_SENT"
    }
}
