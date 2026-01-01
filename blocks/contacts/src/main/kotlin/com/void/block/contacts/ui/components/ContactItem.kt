package com.void.block.contacts.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.void.block.contacts.domain.Contact
import com.void.slate.design.theme.TerminalStandard

/**
 * Display a single contact in a list.
 */
@Composable
fun ContactItem(
    contact: Contact,
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
        // Monogram in brackets [A] instead of circular avatar
        Text(
            text = TerminalStandard.monogram(contact.getDisplayNameOrIdentity()),
            style = TerminalStandard.Monogram,
            color = if (contact.blocked) TerminalStandard.Disabled else TerminalStandard.Text
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Contact info
        Column(
            modifier = Modifier.weight(1f)
        ) {
            // Display name or identity
            Text(
                text = contact.identity.toString().uppercase(),
                style = TerminalStandard.Body,
                color = if (contact.blocked) TerminalStandard.Disabled else TerminalStandard.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Display name (if set)
            if (contact.displayName != null) {
                Text(
                    text = "  ${contact.displayName}",
                    style = TerminalStandard.Body,
                    color = TerminalStandard.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Status indicators - text-based
        if (contact.verified) {
            Text(
                text = "[verified]",
                style = TerminalStandard.Caption,
                color = TerminalStandard.Text
            )
        }

        if (contact.blocked) {
            Text(
                text = "[blocked]",
                style = TerminalStandard.Caption,
                color = TerminalStandard.Disabled
            )
        }
    }
}

/**
 * Avatar for a contact (circle with first letter or icon).
 */
@Composable
private fun ContactAvatar(
    displayName: String,
    verified: Boolean,
    blocked: Boolean,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when {
        blocked -> MaterialTheme.colorScheme.errorContainer
        verified -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    val contentColor = when {
        blocked -> MaterialTheme.colorScheme.onErrorContainer
        verified -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        color = backgroundColor
    ) {
        // Show first letter of display name, or Person icon
        val firstLetter = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: ""

        if (firstLetter.isNotEmpty()) {
            Text(
                text = firstLetter,
                style = MaterialTheme.typography.titleLarge,
                color = contentColor,
                modifier = Modifier
                    .padding(12.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

/**
 * Format last seen timestamp.
 */
private fun formatLastSeen(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Active now"
        diff < 3600_000 -> "Active ${diff / 60_000}m ago"
        diff < 86400_000 -> "Active ${diff / 3600_000}h ago"
        diff < 604800_000 -> "Active ${diff / 86400_000}d ago"
        else -> "Active ${diff / 604800_000}w ago"
    }
}
