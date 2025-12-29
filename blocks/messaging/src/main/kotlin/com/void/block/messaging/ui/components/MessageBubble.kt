package com.void.block.messaging.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.void.block.messaging.domain.Message
import com.void.block.messaging.domain.MessageContent
import com.void.block.messaging.domain.MessageDirection
import com.void.block.messaging.domain.MessageStatus
import java.text.SimpleDateFormat
import java.util.*

/**
 * Message bubble component for chat screen.
 * Shows message content with appropriate styling for sent/received messages.
 */
@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier
) {
    val isOutgoing = message.direction == MessageDirection.OUTGOING

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isOutgoing) 16.dp else 4.dp,
                        bottomEnd = if (isOutgoing) 4.dp else 16.dp
                    )
                )
                .background(
                    if (isOutgoing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
                .padding(12.dp)
        ) {
            // Message content
            when (val content = message.content) {
                is MessageContent.Text -> {
                    Text(
                        text = content.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isOutgoing) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                is MessageContent.Image -> {
                    Text(
                        text = "📷 Image",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isOutgoing) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                is MessageContent.File -> {
                    Text(
                        text = "📎 ${content.fileName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isOutgoing) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                is MessageContent.System -> {
                    Text(
                        text = content.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Timestamp and status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOutgoing) {
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    }
                )

                if (isOutgoing) {
                    Spacer(modifier = Modifier.width(4.dp))
                    MessageStatusIndicator(message.status)
                }
            }

            // Expiry indicator if message will expire
            if (message.expiresAt != null) {
                Spacer(modifier = Modifier.height(4.dp))
                val remainingTime = message.expiresAt - System.currentTimeMillis()
                if (remainingTime > 0) {
                    Text(
                        text = "⏱️ ${formatDuration(remainingTime)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOutgoing) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Status indicator for outgoing messages.
 */
@Composable
private fun MessageStatusIndicator(status: MessageStatus) {
    val (icon, color) = when (status) {
        MessageStatus.SENDING -> "⏳" to MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
        MessageStatus.SENT -> "✓" to MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
        MessageStatus.DELIVERED -> "✓✓" to MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
        MessageStatus.READ -> "✓✓" to MaterialTheme.colorScheme.secondary
        MessageStatus.FAILED -> "⚠️" to MaterialTheme.colorScheme.error
        MessageStatus.EXPIRED -> "⏱️" to MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
    }

    Text(
        text = icon,
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}

/**
 * Format timestamp for message display.
 */
private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Just now" // Less than 1 minute
        diff < 3600_000 -> { // Less than 1 hour
            val minutes = (diff / 60_000).toInt()
            "$minutes min ago"
        }
        diff < 86400_000 -> { // Less than 1 day (same day)
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
        diff < 172800_000 -> { // Less than 2 days (yesterday)
            "Yesterday " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
        else -> {
            SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }
}

/**
 * Format duration for expiry countdown.
 */
private fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        days > 0 -> "${days}d"
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}
