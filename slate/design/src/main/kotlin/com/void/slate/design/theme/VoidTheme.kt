package com.void.slate.design.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * VOID Material 3 Theme
 *
 * Privacy-first dark theme optimized for:
 * - OLED displays (true black saves battery)
 * - Low-light viewing (easy on eyes)
 * - High contrast (WCAG AA compliance)
 * - Minimal distraction (focus on content)
 */

/**
 * Dark color scheme (default and primary)
 */
private val VoidDarkColorScheme = darkColorScheme(
    // Primary colors
    primary = VoidColors.Primary,
    onPrimary = VoidColors.OnPrimary,
    primaryContainer = VoidColors.PrimaryVariant,
    onPrimaryContainer = VoidColors.PrimaryLight,

    // Secondary colors
    secondary = VoidColors.Secondary,
    onSecondary = VoidColors.OnSecondary,
    secondaryContainer = VoidColors.SecondaryVariant,
    onSecondaryContainer = VoidColors.SecondaryLight,

    // Background
    background = VoidColors.Background,
    onBackground = VoidColors.OnBackground,

    // Surface
    surface = VoidColors.Surface,
    onSurface = VoidColors.OnSurface,
    surfaceVariant = VoidColors.SurfaceVariant,
    onSurfaceVariant = VoidColors.OnSurfaceVariant,

    // Error
    error = VoidColors.Error,
    onError = VoidColors.OnError,
    errorContainer = VoidColors.ErrorContainer,
    onErrorContainer = VoidColors.OnErrorContainer,

    // Outline
    outline = VoidColors.Outline,
    outlineVariant = VoidColors.OutlineVariant,

    // Scrim
    scrim = VoidColors.Scrim
)

/**
 * Light color scheme (minimal use - VOID is dark-first)
 * Provided for accessibility or user preference
 */
private val VoidLightColorScheme = lightColorScheme(
    // Primary colors
    primary = VoidColors.PrimaryVariant,
    onPrimary = Color.White,
    primaryContainer = VoidColors.PrimaryLight,
    onPrimaryContainer = VoidColors.PrimaryVariant,

    // Secondary colors
    secondary = VoidColors.SecondaryVariant,
    onSecondary = Color.White,
    secondaryContainer = VoidColors.SecondaryLight,
    onSecondaryContainer = VoidColors.SecondaryVariant,

    // Background
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1C1B1F),

    // Surface
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFF3F3F3),
    onSurfaceVariant = Color(0xFF49454F),

    // Error
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    // Outline
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFC4C6D0),

    // Scrim
    scrim = Color(0xFF000000)
)

/**
 * VOID Material 3 Theme Composable
 *
 * @param darkTheme Force dark theme (default: true for privacy)
 * @param dynamicColor Use Android 12+ dynamic colors (default: false to maintain brand)
 * @param content The composable content to theme
 */
@Composable
fun VoidTheme(
    darkTheme: Boolean = true, // Always dark by default for privacy
    dynamicColor: Boolean = false, // Disable dynamic colors to maintain brand
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Dynamic color is available on Android 12+
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // Use VOID theme
        darkTheme -> VoidDarkColorScheme
        else -> VoidLightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window

            // Set status bar color to match background
            window.statusBarColor = colorScheme.background.toArgb()

            // Set navigation bar color to match background
            window.navigationBarColor = colorScheme.background.toArgb()

            // Set status bar icons to light (for dark background)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme

            // Set navigation bar icons to light (for dark background)
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VoidTypography.Typography,
        content = content
    )
}

/**
 * Extension to access VOID-specific colors
 */
val ColorScheme.voidColors: VoidColorsExtension
    @Composable
    get() = VoidColorsExtension

/**
 * VOID-specific color extensions beyond Material 3
 */
object VoidColorsExtension {
    val success: Color @Composable get() = VoidColors.Success
    val onSuccess: Color @Composable get() = VoidColors.OnSuccess
    val successContainer: Color @Composable get() = VoidColors.SuccessContainer
    val onSuccessContainer: Color @Composable get() = VoidColors.OnSuccessContainer

    val warning: Color @Composable get() = VoidColors.Warning
    val onWarning: Color @Composable get() = VoidColors.OnWarning
    val warningContainer: Color @Composable get() = VoidColors.WarningContainer
    val onWarningContainer: Color @Composable get() = VoidColors.OnWarningContainer

    val ghostMode: Color @Composable get() = VoidColors.GhostMode
    val shadowMode: Color @Composable get() = VoidColors.ShadowMode
    val memoryMode: Color @Composable get() = VoidColors.MemoryMode
    val archiveMode: Color @Composable get() = VoidColors.ArchiveMode

    val identityPrimary: Color @Composable get() = VoidColors.IdentityPrimary
    val identitySecondary: Color @Composable get() = VoidColors.IdentitySecondary
    val identitySeparator: Color @Composable get() = VoidColors.IdentitySeparator

    val rhythmPadBackground: Color @Composable get() = VoidColors.RhythmPadBackground
    val rhythmPadHint: Color @Composable get() = VoidColors.RhythmPadHint
}

/**
 * Extension to access VOID-specific typography
 */
val androidx.compose.material3.Typography.voidTypography: VoidTypographyExtension
    @Composable
    get() = VoidTypographyExtension

/**
 * VOID-specific typography extensions
 */
object VoidTypographyExtension {
    val identity @Composable get() = VoidTypography.Identity
    val identityLarge @Composable get() = VoidTypography.IdentityLarge
    val identitySmall @Composable get() = VoidTypography.IdentitySmall
    val recoveryWord @Composable get() = VoidTypography.RecoveryWord
    val rhythmHint @Composable get() = VoidTypography.RhythmHint
    val messageText @Composable get() = VoidTypography.MessageText
    val timestamp @Composable get() = VoidTypography.Timestamp
}
