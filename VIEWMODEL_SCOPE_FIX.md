# ViewModel Scoping Fix - Rhythm Setup Navigation Issue

## The Problem

When navigating from Rhythm Setup → Rhythm Confirm, the pattern was being lost because **each composable route was creating its own instance** of RhythmSetupViewModel.

### What Was Happening:

1. **RHYTHM_SETUP route:**
   - Creates RhythmSetupViewModel #1
   - User taps pattern → stored in ViewModel #1
   - State = `PatternCreated(pattern)`
   - Navigates to RHYTHM_CONFIRM ✅

2. **RHYTHM_CONFIRM route:**
   - Creates **NEW** RhythmSetupViewModel #2 ❌
   - State = `Idle` (fresh ViewModel)
   - Pattern is `null` ❌
   - Goes back to previous screen ❌

### Evidence from Logs:

```
VOID_DEBUG: RhythmSetupViewModel.onPatternCreated called with pattern: [199, 442, 203, 441] ✅
VOID_DEBUG: RhythmSetupViewModel state updated to: PatternCreated ✅

[Navigation happens]

VOID_DEBUG: RhythmSetupViewModel initialized, initial state: Idle ❌ (NEW ViewModel!)
VOID_DEBUG: Pattern from setup state: null ❌
VOID_DEBUG: Pattern is null, going back to previous screen ❌
```

## The Fix

Use Navigation backstack entries to **share ViewModels** between routes:

### Before (Broken):
```kotlin
composable(Routes.RHYTHM_CONFIRM) {
    val setupViewModel: RhythmSetupViewModel = koinViewModel() // Creates NEW instance!
    // ...
}
```

### After (Fixed):
```kotlin
composable(Routes.RHYTHM_CONFIRM) {
    // Get the SAME ViewModel instance from the RHYTHM_SETUP backstack entry
    val setupBackStackEntry = navController.getBackStackEntry(Routes.RHYTHM_SETUP)
    val setupViewModel: RhythmSetupViewModel = koinViewModel(viewModelStoreOwner = setupBackStackEntry)
    // ...
}
```

## How It Works

**Navigation Backstack:**
```
[Identity] → [Rhythm Setup] → [Rhythm Confirm]
                    ↑
                    |
                    └── ViewModel stored here, shared with Rhythm Confirm
```

By using `navController.getBackStackEntry(Routes.RHYTHM_SETUP)` as the `viewModelStoreOwner`, we tell Koin to use the ViewModel from the RHYTHM_SETUP backstack entry instead of creating a new one.

## Files Modified

1. **app/src/main/kotlin/com/void/app/navigation/VoidNavGraph.kt**
   - Line 91-92: RHYTHM_CONFIRM now shares RhythmSetupViewModel with RHYTHM_SETUP
   - Line 143-144: RHYTHM_RECOVERY now shares RhythmConfirmViewModel with RHYTHM_CONFIRM

## Expected Behavior After Fix

1. User creates rhythm pattern on RHYTHM_SETUP screen ✅
2. Pattern stored in RhythmSetupViewModel ✅
3. Navigate to RHYTHM_CONFIRM ✅
4. RHYTHM_CONFIRM uses **SAME** ViewModel instance ✅
5. Pattern is available: `state = PatternCreated(pattern)` ✅
6. User can confirm the pattern ✅
7. Navigate to RHYTHM_RECOVERY to show recovery phrase ✅

## Testing

Run the app and complete the flow:
```bash
./gradlew installDebug
```

1. Identity screen → Continue
2. Rhythm Setup → Tap 4+ times → **Continue should now navigate to Confirm screen!** ✅
3. Rhythm Confirm → Repeat pattern → Continue
4. Recovery Phrase → Continue
5. Messages screen ✅

## Root Cause

The root cause was a **ViewModel scoping issue**. In Jetpack Compose Navigation:
- Each `composable()` block gets its own ViewModelStore by default
- `koinViewModel()` without a `viewModelStoreOwner` creates a ViewModel scoped to that composable
- When the composable is removed from composition (e.g., navigating away), the ViewModel is cleared
- To share ViewModels between routes, you must explicitly specify the backstack entry as the owner

This is a common pattern in Navigation Compose when you need to share state between consecutive screens in a flow.
