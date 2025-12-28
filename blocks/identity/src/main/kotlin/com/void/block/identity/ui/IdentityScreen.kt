package com.void.block.identity.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.void.slate.state.LoadingState
import org.koin.androidx.compose.koinViewModel

/**
 * Identity Screen - displays the user's 3-word identity.
 * 
 * This screen is completely self-contained within the identity block.
 * It only communicates with other blocks via the EventBus.
 */
@Composable
fun IdentityScreen(
    viewModel: IdentityViewModel = koinViewModel(),
    onComplete: () -> Unit,
    onSkip: (() -> Unit)?
) {
    val state by viewModel.state.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    
    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is IdentityEffect.NavigateToNext -> onComplete()
                is IdentityEffect.CopyToClipboard -> {
                    clipboardManager.setText(AnnotatedString(effect.text))
                }
                is IdentityEffect.ShowError -> {
                    // Show error toast/snackbar
                }
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Title
        Text(
            text = "Your Identity",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "This is your unique 3-word address.\nShare it to let others message you.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        // Identity Display
        when (val loadingState = state.loadingState) {
            is LoadingState.Loading -> {
                CircularProgressIndicator()
            }
            is LoadingState.Error -> {
                Text(
                    text = loadingState.message,
                    color = MaterialTheme.colorScheme.error
                )
            }
            is LoadingState.Success, is LoadingState.Idle -> {
                state.identity?.let { identity ->
                    IdentityCard(
                        identity = identity.formatted,
                        isRegenerating = state.isRegenerating,
                        onCopy = { viewModel.onIntent(IdentityIntent.CopyToClipboard) }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(48.dp))
        
        // Regenerate Button
        AnimatedVisibility(
            visible = state.canRegenerate,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            OutlinedButton(
                onClick = { viewModel.onIntent(IdentityIntent.Regenerate) },
                enabled = !state.isRegenerating
            ) {
                if (state.isRegenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Generate New Identity")
                }
            }
        }
        
        if (state.regenerateCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Regenerated ${state.regenerateCount} times",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Confirm Button
        Button(
            onClick = { viewModel.onIntent(IdentityIntent.Confirm) },
            enabled = state.canConfirm,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("I like this identity")
        }
    }
}

/**
 * Card displaying the 3-word identity.
 */
@Composable
private fun IdentityCard(
    identity: String,
    isRegenerating: Boolean,
    onCopy: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onCopy)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // The 3 words
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                identity.split(".").forEachIndexed { index, word ->
                    if (index > 0) {
                        Text(
                            text = ".",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Light,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = word,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isRegenerating) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "Tap to copy",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
