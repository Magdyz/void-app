@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.void.slate.design.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.void.slate.design.theme.VoidTheme

/**
 * VOID Top App Bar
 *
 * Standard top navigation bar with VOID styling.
 * Transparent background for immersive feel.
 */
@Composable
fun VoidTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        modifier = modifier,
        navigationIcon = navigationIcon ?: {},
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

/**
 * VOID Centered Top Bar
 *
 * Top bar with centered title.
 * Good for simple screens without much context.
 */
@Composable
fun VoidCenteredTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        modifier = modifier,
        navigationIcon = navigationIcon ?: {},
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

/**
 * VOID Large Top Bar
 *
 * Large collapsing top bar for main screens.
 * Title collapses on scroll.
 */
@Composable
fun VoidLargeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    LargeTopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        modifier = modifier,
        navigationIcon = navigationIcon ?: {},
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.largeTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

/**
 * Back Navigation Icon
 *
 * Standard back button for navigation.
 */
@Composable
fun BackNavigationIcon(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Navigate back"
        )
    }
}

/**
 * Menu Action Icon
 *
 * Standard overflow menu icon.
 */
@Composable
fun MenuActionIcon(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = "More options"
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// Previews
// ═══════════════════════════════════════════════════════════════════

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun VoidTopBarPreview() {
    VoidTheme {
        VoidTopBar(
            title = "VOID",
            navigationIcon = { BackNavigationIcon(onClick = { }) },
            actions = {
                MenuActionIcon(onClick = { })
            }
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun VoidCenteredTopBarPreview() {
    VoidTheme {
        VoidCenteredTopBar(
            title = "Settings",
            navigationIcon = { BackNavigationIcon(onClick = { }) }
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun VoidTopBarSimplePreview() {
    VoidTheme {
        VoidTopBar(
            title = "Messages"
        )
    }
}
