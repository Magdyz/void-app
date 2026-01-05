# Key Derivation Debug Plan

## Problem
- Sender and receiver have correct three-word identities for each other
- Contacts already exist (verified)
- Decryption still fails with "MAC verification failed"
- This means derived keys don't match actual keys

## Debug Steps

### 1. Check Sender's View of Receiver
```kotlin
// In ChatViewModel or MessageRepository before sending
val contact = contactRepository.getContact(recipientId)
Log.e("KEY_DEBUG", "=== SENDER's view of RECEIVER ===")
Log.e("KEY_DEBUG", "Three words: ${contact.identity}")
Log.e("KEY_DEBUG", "Stored seed (first 16 bytes): ${contact.identitySeed.take(16).joinToString("") { "%02x".format(it) }}")
Log.e("KEY_DEBUG", "Stored publicKey: ${contact.publicKey.joinToString("") { "%02x".format(it) }}")
```

### 2. Check Receiver's Actual Keys
```kotlin
// In IdentityRepository on receiver device
val identity = getIdentity()
val publicKey = getPublicEncryptionKey()
Log.e("KEY_DEBUG", "=== RECEIVER's actual keys ===")
Log.e("KEY_DEBUG", "Three words: ${identity?.formatted}")
Log.e("KEY_DEBUG", "Actual seed (first 16 bytes): ${identity?.seed?.take(16)?.joinToString("") { "%02x".format(it) }}")
Log.e("KEY_DEBUG", "Actual publicKey: ${publicKey?.joinToString("") { "%02x".format(it) }}")
```

### 3. Test Derivation on Both Devices
```kotlin
// On BOTH devices, derive keys from receiver's three words
val threeWords = "word1.word2.word3"  // Use receiver's actual words
val derivedSeed = MessageDigest.getInstance("SHA-256").digest(threeWords.toByteArray())
val derivedKeyPair = crypto.deriveKeyPairFromSeed(derivedSeed, "encryption")

Log.e("KEY_DEBUG", "=== Derived from three words: $threeWords ===")
Log.e("KEY_DEBUG", "Derived seed (first 16 bytes): ${derivedSeed.take(16).joinToString("") { "%02x".format(it) }}")
Log.e("KEY_DEBUG", "Derived publicKey: ${derivedKeyPair.publicKey.joinToString("") { "%02x".format(it) }}")
```

## Expected Results

**If working correctly:**
- Sender's stored publicKey for receiver = Receiver's actual publicKey
- Both devices derive the same publicKey from receiver's three words
- Derived publicKey = Receiver's actual publicKey

**Possible mismatches:**
1. **Seed mismatch**: Three words → different seeds (character encoding, case, whitespace)
2. **Key derivation mismatch**: Same seed → different keys (crypto.deriveKeyPairFromSeed bug)
3. **Storage mismatch**: Receiver's stored keys ≠ what they should be (regeneration bug)

## Quick Test Commands

Run on BOTH devices and compare outputs:
```bash
# Get receiver's three-word identity
adb logcat | grep "Three words:"

# Get sender's stored keys for receiver
adb logcat | grep "Stored publicKey:"

# Get receiver's actual keys
adb logcat | grep "Actual publicKey:"

# Get derived keys from three words
adb logcat | grep "Derived publicKey:"
```

## Next Steps

Based on which values differ, we'll know exactly where to fix the bug.
