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
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar (first letter of contact name)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = contactName.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Content
        Column(
            modifier = Modifier.weight(1f)
        ) {
            // Contact name
            Text(
                text = contactName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (conversation.hasUnreadMessages()) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Message preview
            Text(
                text = conversation.getPreviewText(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (conversation.hasUnreadMessages()) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Right side: timestamp and unread badge
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Timestamp
            conversation.lastMessageAt?.let { timestamp ->
                Text(
                    text = formatConversationTime(timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (conversation.hasUnreadMessages()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            // Unread badge
            if (conversation.hasUnreadMessages()) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (conversation.unreadCount > 9) "9+" else conversation.unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Muted indicator
            if (conversation.isMuted) {
                Text(
                    text = "🔇",
                    style = MaterialTheme.typography.labelSmall
                )
            }
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
