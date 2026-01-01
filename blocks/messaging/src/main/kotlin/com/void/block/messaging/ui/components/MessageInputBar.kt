package com.void.block.messaging.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.void.slate.design.theme.TerminalStandard

/**
 * Message input bar for chat screen.
 * Allows typing and sending messages.
 */
@Composable
fun MessageInputBar(
    message: String,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = TerminalStandard.Background
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Text input with bracket styling
            OutlinedTextField(
                value = message,
                onValueChange = onMessageChange,
                modifier = Modifier
                    .weight(1f)
                    .border(
                        width = 1.dp,
                        color = TerminalStandard.Border,
                        shape = RoundedCornerShape(0.dp)
                    ),
                placeholder = {
                    Text(
                        text = "[ type message... ]",
                        style = TerminalStandard.Input,
                        color = TerminalStandard.TextSecondary
                    )
                },
                textStyle = TerminalStandard.Input,
                shape = RoundedCornerShape(0.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (message.isNotBlank() && enabled) {
                            onSend()
                        }
                    }
                ),
                enabled = enabled,
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TerminalStandard.Text,
                    unfocusedBorderColor = TerminalStandard.Border,
                    focusedTextColor = TerminalStandard.Text,
                    unfocusedTextColor = TerminalStandard.Text,
                    disabledTextColor = TerminalStandard.Disabled,
                    cursorColor = TerminalStandard.Text,
                    focusedContainerColor = TerminalStandard.Background,
                    unfocusedContainerColor = TerminalStandard.Background
                )
            )

            // Send button - text-based
            TextButton(
                onClick = onSend,
                enabled = enabled && message.isNotBlank(),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (enabled && message.isNotBlank()) TerminalStandard.Text else TerminalStandard.Disabled,
                    contentColor = TerminalStandard.Background,
                    disabledContainerColor = TerminalStandard.Disabled,
                    disabledContentColor = TerminalStandard.Background
                )
            ) {
                Text(
                    text = TerminalStandard.bracketLabel("SEND"),
                    style = TerminalStandard.Button
                )
            }
        }
    }
}

/**
 * Typing indicator to show when other person is typing.
 */
@Composable
fun TypingIndicator(
    contactName: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = "$contactName is typing",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Animated dots
        TypingDots()
    }
}

/**
 * Animated typing dots indicator.
 */
@Composable
private fun TypingDots() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        repeat(3) {
            Text(
                text = "•",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
