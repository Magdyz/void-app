package com.void.slate.design.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.void.slate.design.theme.VoidTheme

/**
 * VOID Primary Button
 *
 * Standard filled button for primary actions.
 * Large tap target (48dp min height) for accessibility.
 */
@Composable
fun VoidButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .height(56.dp),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        content = content
    )
}

/**
 * VOID Secondary Button
 *
 * Outlined button for secondary actions.
 * Same size as primary button for consistency.
 */
@Composable
fun VoidSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .height(56.dp),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        content = content
    )
}

/**
 * VOID Text Button
 *
 * Low-emphasis button for tertiary actions.
 * Useful for "Cancel", "Skip", etc.
 */
@Composable
fun VoidTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        content = content
    )
}

/**
 * VOID Danger Button
 *
 * Destructive action button (e.g., "Delete Account", "Clear Data")
 */
@Composable
fun VoidDangerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .height(56.dp),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        content = content
    )
}

// ═══════════════════════════════════════════════════════════════════
// Previews
// ═══════════════════════════════════════════════════════════════════

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun VoidButtonPreview() {
    VoidTheme {
        VoidButton(onClick = { }) {
            Text("Primary Button")
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun VoidSecondaryButtonPreview() {
    VoidTheme {
        VoidSecondaryButton(onClick = { }) {
            Text("Secondary Button")
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun VoidTextButtonPreview() {
    VoidTheme {
        VoidTextButton(onClick = { }) {
            Text("Text Button")
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun VoidDangerButtonPreview() {
    VoidTheme {
        VoidDangerButton(onClick = { }) {
            Text("Danger Button")
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun VoidButtonDisabledPreview() {
    VoidTheme {
        VoidButton(onClick = { }, enabled = false) {
            Text("Disabled Button")
        }
    }
}
