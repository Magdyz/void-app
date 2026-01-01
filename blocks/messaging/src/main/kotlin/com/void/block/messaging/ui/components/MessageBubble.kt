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
import com.void.slate.design.theme.TerminalStandard
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

    // Terminal Standard: No bubbles, just left/right alignment with arrow indicators
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.widthIn(max = 280.dp),
            horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
        ) {
            // Message content with arrow indicator
            when (val content = message.content) {
                is MessageContent.Text -> {
                    Text(
                        text = if (isOutgoing) {
                            TerminalStandard.sentMessage(content.text)
                        } else {
                            TerminalStandard.receivedMessage(content.text)
                        },
                        style = TerminalStandard.Body,
                        color = TerminalStandard.Text
                    )
                }
                is MessageContent.Image -> {
                    Text(
                        text = if (isOutgoing) {
                            TerminalStandard.sentMessage("[image]")
                        } else {
                            TerminalStandard.receivedMessage("[image]")
                        },
                        style = TerminalStandard.Body,
                        color = TerminalStandard.Text
                    )
                }
                is MessageContent.File -> {
                    Text(
                        text = if (isOutgoing) {
                            TerminalStandard.sentMessage("[file: ${content.fileName}]")
                        } else {
                            TerminalStandard.receivedMessage("[file: ${content.fileName}]")
                        },
                        style = TerminalStandard.Body,
                        color = TerminalStandard.Text
                    )
                }
                is MessageContent.System -> {
                    Text(
                        text = "// ${content.message}",
                        style = TerminalStandard.Caption,
                        color = TerminalStandard.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Timestamp and status
            Text(
                text = buildString {
                    append(formatTimestamp(message.timestamp))
                    if (isOutgoing) {
                        append(" ")
                        append(getStatusIndicator(message.status))
                    }
                },
                style = TerminalStandard.Caption,
                color = TerminalStandard.TextSecondary
            )
        }
    }
}

/**
 * Status indicator for outgoing messages - text-based.
 */
private fun getStatusIndicator(status: MessageStatus): String {
    return when (status) {
        MessageStatus.SENDING -> "[...]"
        MessageStatus.SENT -> "[sent]"
        MessageStatus.DELIVERED -> "[delivered]"
        MessageStatus.READ -> "[read]"
        MessageStatus.FAILED -> "[failed]"
        MessageStatus.EXPIRED -> "[expired]"
    }
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
