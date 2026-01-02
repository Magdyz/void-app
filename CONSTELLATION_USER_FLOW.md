# Constellation Lock - User Flow & Backend Process

## 🎯 Overview

The Constellation Lock replaces the previous rhythm-based authentication with a **visual tap-sequence system**. Users tap stars in a unique constellation pattern to secure their identity.

---

## 📱 First-Time User Flow (Onboarding)

### What the User Sees:

#### **Step 1: Identity Generation**
**Screen:** Identity Generation
- **User sees:** 3-word identity being generated (e.g., "warrior-ocean-moon")
- **User action:** Wait for generation to complete
- **Duration:** ~2-3 seconds

**Backend Process:**
```
1. Generate 256-bit cryptographic seed using Tink CryptoProvider
2. Derive Ed25519 key pair from seed (for messaging)
3. Derive X25519 key pair from seed (for encryption)
4. Generate 3-word BIP-39 mnemonic for display
5. Store identity seed in SQLCipher encrypted database
6. Create hardware-backed Keystore key
```

**Files Involved:**
- `IdentityRepository.kt` - Manages identity creation
- `TinkCryptoProvider.kt` - Generates cryptographic material
- `SqlCipherStorage.kt` - Stores encrypted identity data
- `KeystoreManager.kt` - Creates hardware keys

---

#### **Step 2: Constellation Setup**
**Screen:** Create Your Constellation Pattern
- **User sees:**
  - A beautiful, unique star constellation (different for every user)
  - Instruction: "Tap 6-8 stars in sequence"
  - Progress dots showing how many stars tapped
  - Pattern quality indicator (Weak/Good/Strong)

- **User action:**
  1. Study the constellation
  2. Choose memorable stars (corners, shapes, landmarks)
  3. Tap 6-8 stars in a specific order
  4. Ensure pattern quality is at least 50%
  5. Press "Continue"

**Backend Process:**
```
1. StarGenerator derives constellation from identity seed:
   - Uses CryptoProvider.derive(seed, "m/constellation/0")
   - Creates deterministic Random with derived seed
   - Generates 50 nodes at normalized positions (0.0-1.0)
   - Renders to bitmap with seed-based colors
   - Generates verification hash

2. On each tap:
   - Record tap coordinates (x, y, timestamp)
   - Normalize to screen-independent coordinates
   - Calculate pattern quality:
     * Quadrant distribution (all in one = penalty)
     * Point spacing (too close = penalty)
     * Linear patterns (horizontal/vertical = penalty)
   - Update quality indicator

3. When user presses Continue:
   - Validate minimum 6 stars
   - Validate quality >= 50%
   - Navigate to confirmation screen
```

**Files Involved:**
- `StarGenerator.kt` - Creates deterministic constellation
- `ConstellationSetupViewModel.kt` - Manages tap state
- `ConstellationMatcher.kt` - Calculates quality
- `ConstellationView.kt` - Renders constellation and captures taps

**Visual Example:**
```
┌────────────────────────────────┐
│  Create Your Constellation     │
│  Tap 6-8 stars in sequence     │
│                                │
│  Progress: ●●●●○○○○            │
│                                │
│    ·    *       ·   *          │
│        ·   *                   │
│   *         ·      *           │
│     ·    *     ·               │
│  *         ·         *         │
│      ·         *               │
│   [Tapped stars marked]        │
│                                │
│  Quality: ████████░░ Good      │
│                                │
│  [Reset]         [Continue]    │
└────────────────────────────────┘
```

---

#### **Step 3: Confirm Your Pattern**
**Screen:** Confirm Your Pattern
- **User sees:**
  - Same constellation
  - Instruction: "Tap the same 6 stars in the same order"
  - Progress dots (must match number from setup)

- **User action:**
  1. Recreate the exact same pattern
  2. Tap same stars in same order
  3. Automatic verification on completion

**Backend Process:**
```
1. User taps stars (same as setup)

2. On tap completion:
   - Normalize tap coordinates
   - Quantize to 64×64 grid:
     * cellX = (normalizedX × 64).toInt()
     * cellY = (normalizedY × 64).toInt()
   - Compare grid cells with original pattern
   - Must match exactly (order matters)

3. If patterns match:
   - Serialize pattern to JSON
   - Encrypt with hardware Keystore key:
     * KeystoreManager.generateKey("constellation_master_key")
     * KeystoreManager.encrypt(key, patternJSON)
   - Store encrypted pattern in SQLCipher
   - Store verification hash
   - Store algorithm version
   - Navigate to Messages

4. If patterns don't match:
   - Show error: "Patterns don't match. Try again."
   - Navigate back to setup
```

**Files Involved:**
- `ConstellationConfirmViewModel.kt` - Manages confirmation
- `ConstellationMatcher.kt` - Grid-based matching
- `StarQuantizer.kt` - 64×64 grid quantization
- `ConstellationSecurityManager.kt` - Encryption and storage

**Security Details:**
```
Grid Quantization (64×64):
- Tap at (540px, 960px) on 1080×1920 screen
- Normalized: (0.5, 0.5)
- Grid cell: (32, 32)

Why grid?
- Device-independent (works on all screen sizes)
- Eliminates tolerance ambiguity
- ~1.5% tolerance per cell
- 64² = 4,096 possible positions per star
- 6 stars with order = ~72 bits entropy
```

---

#### **Step 4: Ready to Use**
**Screen:** Messages List
- **User sees:** Main app interface
- **Backend:** Identity and constellation pattern fully configured

**What's Stored:**
```
SQLCipher Database (encrypted):
├── identity.seed (256-bit master seed)
├── identity.publicKey (Ed25519 for signing)
├── identity.privateKey (Ed25519, encrypted)
├── constellation.pattern.real.encrypted (pattern JSON, Keystore-encrypted)
├── constellation.verification_hash (for integrity check)
└── constellation.algorithm_version (for migration)

Android Keystore (hardware):
├── constellation_master_key (AES-256, StrongBox if available)
└── Used to encrypt/decrypt pattern
```

---

## 🔓 Returning User Flow (Unlocking)

### What the User Sees:

#### **Unlock Screen**
**Screen:** Unlock
- **User sees:**
  - Their unique constellation (same as setup)
  - Instruction: "Tap your constellation pattern"
  - Attempts remaining (if failures occurred)

- **User action:**
  1. Tap their memorized pattern
  2. Automatic unlock attempt after 6th star

**Backend Process:**
```
1. On app start:
   - AppStateManager checks: hasRealConstellation()?
   - If yes → Navigate to CONSTELLATION_UNLOCK
   - If no → Navigate to IDENTITY_GENERATE (first time)

2. Unlock screen loads:
   - Check lockout status:
     * Read lockout_end_time from storage
     * If current_time < lockout_end → Show lockout timer
   - Generate constellation from stored identity seed
   - Verify constellation integrity (hash matches)
   - Display constellation

3. User taps pattern:
   - Collect taps (up to 8 stars max)
   - After 6th tap, auto-attempt unlock

4. Unlock attempt:
   - Normalize taps to 0.0-1.0 coordinates
   - Quantize to 64×64 grid
   - Load encrypted pattern from storage
   - Decrypt with Keystore key
   - Compare grid cells (CONSTANT-TIME):
     * var match = true
     * for each point:
       if (grid[i] != stored[i]) match = false
     * return match (prevents timing attacks)

5. If match:
   - Reset failed_attempts counter
   - Get identity seed hash
   - Navigate to Messages
   - Emit UnlockSuccessful event

6. If no match:
   - Increment failed_attempts
   - Increment total_attempts
   - Check thresholds:
     * 5 attempts → 5 minute lockout
     * 20 total → PANIC WIPE (delete all data)
   - Show error with attempts remaining
   - Reset tap state for retry
```

**Files Involved:**
- `AppStateManager.kt` - Determines start route
- `ConstellationUnlockViewModel.kt` - Manages unlock state
- `ConstellationSecurityManager.kt` - Decrypt, verify, lockout logic
- `ConstellationMatcher.kt` - Constant-time matching

**Security Features:**
```
Lockout System:
┌─────────────────────────────────────┐
│ Attempt 1-4: Normal operation       │
│ Attempt 5:   5 minute lockout       │
│              (persists across kills) │
│ Total 20:    PANIC WIPE             │
│              (delete identity)       │
└─────────────────────────────────────┘

Constant-Time Matching:
- Prevents timing side-channel attacks
- Always checks all points
- Returns result after full comparison
```

**Lockout Screen:**
```
┌────────────────────────────────┐
│     Too Many Attempts          │
│                                │
│   Try again in 4:37            │
│                                │
│  [Use Recovery Phrase]         │
└────────────────────────────────┘
```

---

## 🔐 Security Architecture

### Encryption Layers:

```
┌─────────────────────────────────────────────────┐
│  USER TAP PATTERN                               │
│  ├─ Tap 1: (123, 456) → Grid (7, 14)          │
│  ├─ Tap 2: (789, 234) → Grid (48, 7)          │
│  └─ ... (6-8 taps total)                       │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│  PATTERN JSON (Plaintext)                       │
│  {                                              │
│    "stars": [                                   │
│      {"normalizedX": 0.114, "normalizedY": ...},│
│      ...                                        │
│    ],                                           │
│    "quality": 85                                │
│  }                                              │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│  ANDROID KEYSTORE (Hardware)                    │
│  KeystoreManager.encrypt(                       │
│    alias: "constellation_master_key",           │
│    plaintext: patternJSON                       │
│  )                                              │
│  → Uses AES-256-GCM                             │
│  → Key never leaves hardware (StrongBox)        │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│  ENCRYPTED PATTERN (Binary)                     │
│  [IV][Ciphertext][Auth Tag]                     │
│  (unreadable without Keystore key)              │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│  SQLCIPHER DATABASE (Encrypted)                 │
│  - Database encrypted with separate key         │
│  - Stores encrypted pattern blob                │
│  - Double encryption: Keystore + SQLCipher      │
└─────────────────────────────────────────────────┘
```

### Why This Is Secure:

1. **Hardware Protection**: Pattern encrypted with hardware-backed key (can't be extracted)
2. **Database Encryption**: SQLCipher encrypts entire database (even encrypted patterns)
3. **Grid Quantization**: Device-independent, no tolerance ambiguity
4. **Constant-Time Matching**: No timing side-channels
5. **Lockout**: 5 attempts → lockout, 20 attempts → wipe
6. **Screenshot Protection**: FLAG_SECURE prevents capture
7. **Deterministic Generation**: Constellation derived from identity (can regenerate)

---

## 🎨 Visual Summary

### Onboarding Flow:
```
First Launch
     ↓
┌─────────────────┐
│ Identity Gen    │ ← Generate 3-word identity
│ warrior-ocean   │   (2-3 seconds)
│     -moon       │
└─────────────────┘
     ↓
┌─────────────────┐
│ Constellation   │ ← Tap 6-8 stars
│   Setup         │   Quality indicator
│   * · * · *     │   [Continue when good]
│ · * · * · * ·   │
└─────────────────┘
     ↓
┌─────────────────┐
│ Confirmation    │ ← Recreate same pattern
│   * · * · *     │   Must match on 64×64 grid
│ · * · * · * ·   │
└─────────────────┘
     ↓
┌─────────────────┐
│ Messages List   │ ← Main app
│ (No messages)   │
└─────────────────┘
```

### Unlock Flow:
```
App Restart
     ↓
┌─────────────────┐
│ Unlock          │ ← Same constellation
│   * · * · *     │   Tap your pattern
│ · * · * · * ·   │
└─────────────────┘
     ↓ (correct)
┌─────────────────┐
│ Messages List   │
└─────────────────┘
     ↓ (5 wrong attempts)
┌─────────────────┐
│ Locked Out      │ ← 5 minute timer
│ Try in 4:37     │   [Use Recovery Phrase]
└─────────────────┘
```

---

## 🔢 Technical Specifications

### Pattern Requirements:
- **Stars**: 6-8 taps required
- **Quality**: Minimum 50% score
- **Spacing**: Minimum 10% of screen between points
- **Grid**: 64×64 quantization (4,096 cells)

### Entropy Calculation:
```
6 stars from 64×64 grid with order:
= P(4096, 6)
= 4096 × 4095 × 4094 × 4093 × 4092 × 4091
≈ 2^71.9 bits

Effective security: ~72 bits
(Stronger than 6-digit PIN: 20 bits)
(Weaker than 12-word phrase: 128 bits)
```

### Performance Targets:
- **Constellation generation**: < 500ms (background thread)
- **Pattern matching**: < 50ms (constant-time)
- **Unlock flow**: < 1 second total

### Storage Footprint:
```
Per pattern:
- Encrypted pattern blob: ~200 bytes
- Verification hash: 32 bytes
- Metadata: ~50 bytes
Total: ~300 bytes per user
```

---

## 🛡️ Security Comparison

| Feature | Constellation | Rhythm (old) | PIN |
|---------|--------------|--------------|-----|
| Entropy | ~72 bits | ~43 bits | ~20 bits |
| Device-independent | ✅ (grid) | ❌ (timing) | ✅ |
| Shoulder-surfing resistant | ⚠️ Medium | ✅ High | ❌ Low |
| Muscle memory | ✅ Yes | ✅ Yes | ✅ Yes |
| Accessible | ⚠️ Requires vision | ✅ Audio feedback | ✅ |
| Hardware-backed | ✅ Keystore | ✅ Keystore | ✅ Keystore |
| Lockout | ✅ 5 attempts | ✅ 5 attempts | ✅ 5 attempts |
| Panic wipe | ✅ 20 total | ✅ 20 total | ✅ 20 total |

---

## 🚨 Recovery Process (Future)

**When User Forgets Pattern:**
1. Click "Forgot pattern?" on unlock screen
2. Enter 12-word recovery phrase
3. Validate phrase matches stored identity
4. Clear constellation pattern
5. Navigate to constellation setup (create new pattern)
6. Keep same identity (messages preserved)

**Backend:**
```kotlin
// In ConstellationSecurityManager
suspend fun recoverFromPhrase(mnemonic: List<String>): Boolean {
    // Validate mnemonic with IdentityRepository
    // Clear pattern: storage.delete(KEY_REAL_PATTERN_ENCRYPTED)
    // Reset security state
    // Navigate to setup
}
```

---

## 📊 Summary

**What User Experiences:**
- Beautiful, personal star constellation
- Simple tap-based authentication
- Visual feedback (quality, attempts)
- Fast unlock (< 1 second)

**What Backend Ensures:**
- ~72 bits of entropy
- Hardware-backed encryption
- Device-independent matching
- Timing attack protection
- Lockout and panic wipe
- Double encryption (Keystore + SQLCipher)

**Trade-offs:**
- ✅ More entropy than rhythm
- ✅ Device-independent (grid vs timing)
- ⚠️ Slightly less shoulder-surfing resistant
- ⚠️ Requires visual interface (accessibility consideration)

The Constellation Lock provides a **strong, usable, and elegant** authentication system that balances security with user experience.
