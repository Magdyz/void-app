package com.void.slate.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.void.slate.design.theme.VoidTheme

/**
 * VOID Card
 *
 * Standard elevated card for content grouping.
 * Used for identity display, message bubbles, settings sections.
 */
@Composable
fun VoidCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        ),
        content = content
    )
}

/**
 * VOID Outlined Card
 *
 * Card with border but no elevation.
 * Used for less prominent content or to reduce visual weight.
 */
@Composable
fun VoidOutlinedCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    OutlinedCard(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline
        ),
        content = content
    )
}

/**
 * VOID Identity Card
 *
 * Special card for displaying 3-word identity.
 * Prominent, monospace typography.
 */
@Composable
fun VoidIdentityCard(
    identity: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    VoidCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Main identity in monospace
            Text(
                text = identity,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.primary
            )

            // Optional subtitle
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * VOID Message Card
 *
 * Card styled for message bubbles.
 * Smaller corner radius for chat feel.
 */
@Composable
fun VoidMessageCard(
    isOutgoing: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = if (isOutgoing) 16.dp else 4.dp,
            bottomEnd = if (isOutgoing) 4.dp else 16.dp
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isOutgoing) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (isOutgoing) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
        ),
        content = content
    )
}

/**
 * VOID Info Card
 *
 * Informational card with colored background.
 * Used for tips, warnings, errors.
 */
@Composable
fun VoidInfoCard(
    modifier: Modifier = Modifier,
    type: InfoCardType = InfoCardType.Info,
    content: @Composable ColumnScope.() -> Unit
) {
    val (containerColor, contentColor) = when (type) {
        InfoCardType.Info -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        InfoCardType.Success -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        InfoCardType.Warning -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        InfoCardType.Error -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        content = content
    )
}

enum class InfoCardType {
    Info,
    Success,
    Warning,
    Error
}

// ═══════════════════════════════════════════════════════════════════
// Previews
// ═══════════════════════════════════════════════════════════════════

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun VoidCardPreview() {
    VoidTheme {
        VoidCard(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Card Title", style = MaterialTheme.typography.titleLarge)
                Text("Card content goes here", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun VoidIdentityCardPreview() {
    VoidTheme {
        VoidIdentityCard(
            identity = "ghost.paper.forty",
            subtitle = "Your VOID identity",
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun VoidMessageCardPreview() {
    VoidTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VoidMessageCard(isOutgoing = false) {
                Text(
                    text = "Incoming message",
                    modifier = Modifier.padding(12.dp)
                )
            }
            VoidMessageCard(isOutgoing = true) {
                Text(
                    text = "Outgoing message",
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun VoidInfoCardPreview() {
    VoidTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VoidInfoCard(type = InfoCardType.Info) {
                Text(
                    text = "This is an info card",
                    modifier = Modifier.padding(16.dp)
                )
            }
            VoidInfoCard(type = InfoCardType.Success) {
                Text(
                    text = "Success!",
                    modifier = Modifier.padding(16.dp)
                )
            }
            VoidInfoCard(type = InfoCardType.Warning) {
                Text(
                    text = "Warning message",
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
