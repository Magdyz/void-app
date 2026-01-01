package com.void.block.messaging.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.void.block.messaging.domain.Conversation
import com.void.slate.design.theme.TerminalStandard
import java.text.SimpleDateFormat
import java.util.*

/**
 * Conversation item for conversation list.
 * Shows contact name, preview, timestamp, and unread badge.
 */
@Composable
fun ConversationItem(
    conversation: Conversation,
    contactName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Monogram in brackets [P] instead of circular avatar
        Text(
            text = TerminalStandard.monogram(contactName),
            style = TerminalStandard.Monogram,
            color = TerminalStandard.Text
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Content
        Column(
            modifier = Modifier.weight(1f)
        ) {
            // Contact name - Bold white if unread, Normal grey if read
            Text(
                text = contactName,
                style = if (conversation.hasUnreadMessages()) TerminalStandard.BodyBold else TerminalStandard.Body,
                color = if (conversation.hasUnreadMessages()) TerminalStandard.Text else TerminalStandard.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Message preview - Indented, same style as name
            Text(
                text = "  ${conversation.getPreviewText()}", // Indented with 2 spaces
                style = TerminalStandard.Body,
                color = if (conversation.hasUnreadMessages()) TerminalStandard.Text else TerminalStandard.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Format timestamp for conversation list.
 */
private fun formatConversationTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Now" // Less than 1 minute
        diff < 3600_000 -> { // Less than 1 hour
            val minutes = (diff / 60_000).toInt()
            "${minutes}m"
        }
        diff < 86400_000 -> { // Less than 1 day (today)
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
        diff < 172800_000 -> { // Yesterday
            "Yesterday"
        }
        diff < 604800_000 -> { // Less than 1 week
            SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
        }
        else -> {
            SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
