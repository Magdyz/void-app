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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.void.slate.design.components.VoidButton
import com.void.slate.design.components.VoidIdentityCard
import com.void.slate.design.components.VoidSecondaryButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
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
    val lifecycleOwner = LocalLifecycleOwner.current

    // Debug: Log state changes
    LaunchedEffect(state) {
        println("VOID_DEBUG: IdentityScreen - State updated: identity=${state.identity?.formatted}, canConfirm=${state.canConfirm}, isRegenerating=${state.isRegenerating}, loadingState=${state.loadingState}")
    }

    // Handle effects - CHANGED TO ROBUST LIFECYCLE COLLECTION
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            println("VOID_DEBUG: Effect collector STARTED")
            viewModel.effects.collect { effect ->
                println("VOID_DEBUG: Effect received in UI: $effect")
                when (effect) {
                    is IdentityEffect.NavigateToNext -> {
                        println("VOID_DEBUG: Executing onComplete()")
                        onComplete()
                    }
                    is IdentityEffect.CopyToClipboard -> {
                        clipboardManager.setText(AnnotatedString(effect.text))
                    }
                    is IdentityEffect.ShowError -> {
                        // Show error toast/snackbar
                    }
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
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.onIntent(IdentityIntent.CopyToClipboard) }
                    ) {
                        VoidIdentityCard(
                            identity = identity.formatted,
                            subtitle = "Tap to copy"
                        )
                    }
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
            VoidSecondaryButton(
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
        VoidButton(
            onClick = { 
                println("VOID_DEBUG: Confirm Button Clicked")
                viewModel.onIntent(IdentityIntent.Confirm) 
            },
            enabled = state.canConfirm,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("I like this identity")
        }
    }
}

