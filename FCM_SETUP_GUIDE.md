# Firebase Cloud Messaging (FCM) Setup Guide

This guide explains how to set up Firebase Cloud Messaging for push notifications in the VOID app.

## Overview

VOID uses Firebase Cloud Messaging (FCM) to deliver **silent push notifications** that wake up your device when new messages arrive. This enables real-time message delivery without constantly polling the server.

**Privacy Note:** FCM notifications contain NO message content - they're just "wake-up signals" that tell your app to check for new messages. All message content is fetched and decrypted locally.

## Prerequisites

- Firebase account (free tier is sufficient)
- Android Studio with the VOID project open
- Access to Firebase Console

---

## Step 1: Create/Access Firebase Project

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Create a new project or select your existing VOID project
3. If creating new:
   - Enter project name (e.g., "VOID")
   - Disable Google Analytics (optional, but recommended for privacy)
   - Click "Create Project"

---

## Step 2: Add Android App to Firebase Project

1. In Firebase Console, click the Android icon to add an Android app
2. Enter the package name: `com.void.app`
   - **IMPORTANT:** This must match exactly
   - Find it in `app/build.gradle.kts` under `applicationId`
3. App nickname (optional): "VOID Play Store"
4. Debug signing certificate SHA-1 (optional): Skip for now
5. Click "Register app"

---

## Step 3: Download google-services.json

1. Firebase Console will prompt you to download `google-services.json`
2. Click **"Download google-services.json"**
3. Save the file to:
   ```
   /Users/magz/Documents/Coding/void-app/app/google-services.json
   ```
   - **CRITICAL:** The file must be in the `app/` directory, not the root!

4. Verify the file location:
   ```bash
   ls -la app/google-services.json
   ```
   Should show the file exists.

---

## Step 4: Download Firebase Service Account (Server-Side)

The edge function needs a Firebase service account to authenticate with FCM API.

### 4.1 Generate Service Account

1. In Firebase Console, click the gear icon → **Project Settings**
2. Go to **Service Accounts** tab
3. Click **"Generate new private key"**
4. Click **"Generate key"** in the confirmation dialog
5. A JSON file will be downloaded (e.g., `void-firebase-adminsdk-xxxxx.json`)

### 4.2 Copy to Supabase Directory

1. Rename the file to `firebase-service-account.json`
2. Move it to:
   ```
   /Users/magz/Documents/Coding/void-app/supabase/firebase-service-account.json
   ```

3. Verify the file exists:
   ```bash
   ls -la supabase/firebase-service-account.json
   ```

**Note:** This file is already in `.gitignore` for security.

---

## Step 5: Deploy Firebase Service Account to Supabase

The edge function needs the service account as an environment variable.

### 5.1 Set Secret in Supabase

```bash
cd supabase

# Read the service account file and set it as a secret
supabase secrets set FIREBASE_SERVICE_ACCOUNT="$(cat firebase-service-account.json)"
```

### 5.2 Verify Secret is Set

```bash
supabase secrets list
```

Should show `FIREBASE_SERVICE_ACCOUNT` in the list.

---

## Step 6: Build and Test

### 6.1 Build the Play Flavor

```bash
# From project root
./gradlew :app:assemblePlayDebug
```

If the build succeeds, Firebase is configured correctly!

### 6.2 Install on Device/Emulator

```bash
./gradlew :app:installPlayDebug
```

### 6.3 Check Logcat for FCM Token

After launching the app, check logcat:

```bash
adb logcat | grep -E "VoidFirebaseService|VoidApp|FCM"
```

**Expected logs:**

```
🔑 FCM token refreshed by Google
✅ FCM token registered after Google refresh
🔔 FCM token registered for new identity
```

**Or if no identity yet:**

```
⚠️  No identity found - cannot register push token yet
✓ Will auto-register when identity is created
```

### 6.4 Verify Token in Database

After the app registers the token, check the database:

```sql
-- In Supabase SQL Editor
SELECT
    mailbox_hash,
    LEFT(fcm_token, 20) || '...' as token_preview,
    expires_at,
    created_at
FROM push_registrations
ORDER BY created_at DESC
LIMIT 5;
```

Should show at least one row with a recent timestamp.

---

## Step 7: Test Push Notifications

### 7.1 Send a Test Message

1. Open the app on Device A
2. Open the app on Device B (or another emulator)
3. Add Device A as a contact on Device B
4. Send a message from Device A to Device B
5. Lock Device B's screen
6. The message should arrive within seconds (silent push)

### 7.2 Check Edge Function Logs

In Supabase Dashboard → Edge Functions → send-push-notification → Logs:

**Success:**
```
New message for mailbox: 197e93ab... at epoch: 1767588133
Push sent successfully to 197e93ab...
```

**Failure (if token not registered):**
```
No FCM token for mailbox 197e93ab... - skipping push
```

If you see "No FCM token", the device didn't register. Check Step 6.3.

---

## Troubleshooting

### Build Fails: "File google-services.json is missing"

**Solution:** Download `google-services.json` from Firebase Console and place it in `app/` directory.

### "No FCM token for mailbox..." in Logs

**Possible causes:**

1. **google-services.json missing** → Complete Steps 3-6
2. **Identity not created yet** → Create identity in app, token will auto-register
3. **Network error during registration** → Restart app, self-healing will retry
4. **Service account not set** → Complete Step 5

**Debug:**
```bash
# Check if token is being generated
adb logcat | grep "FCM token"

# Check database for registrations
supabase db query "SELECT COUNT(*) FROM push_registrations;"
```

### Notifications Not Appearing on Android 13+

**Cause:** User denied POST_NOTIFICATIONS permission

**Solution:**
1. Open app
2. System should show permission dialog automatically
3. Tap "Allow"

If you already denied:
1. Go to Settings → Apps → VOID → Notifications
2. Enable "All VOID notifications"

### Token Expires Every 25 Hours

**This is normal!** VOID rotates mailbox hashes every 25 hours for privacy. The app automatically:
- Re-registers token with new mailbox hash
- Deletes expired registrations from database

Check logs for:
```
✅ FCM self-heal: Token registered on app startup
```

---

## Security Notes

### Why is google-services.json in .gitignore?

While `google-services.json` contains only project identifiers (not secrets), it's a best practice to exclude it from git because:

1. Each developer may use a different Firebase project
2. Prevents accidental commits of test/prod configurations
3. Follows Firebase's recommended security practices

### What's in firebase-service-account.json?

This file contains:
- **Private key** (RSA 2048-bit) - Used to sign OAuth 2.0 JWTs
- **Client email** - Service account identifier
- **Project ID** - Firebase project identifier

**CRITICAL:** This file grants admin access to Firebase. Never commit it to git!

---

## Architecture Summary

```
┌─────────────┐                                    ┌──────────────┐
│  Device A   │                                    │   Device B   │
│             │                                    │              │
│  1. Send    │──────────────────────────────────>│              │
│   Message   │    Encrypted via Supabase API     │              │
└─────────────┘                                    └──────────────┘
                                                           │
                                                           │ 2. DB Trigger
                                                           ▼
                                                   ┌──────────────────┐
                                                   │ Supabase Edge    │
                                                   │ Function         │
                                                   │                  │
                                                   │ • Query FCM token│
                                                   │ • Send silent    │
                                                   │   push (epoch    │
                                                   │   only, no data) │
                                                   └──────────────────┘
                                                           │
                                                           │ 3. FCM API V1
                                                           │    (OAuth 2.0)
                                                           ▼
                                                   ┌──────────────────┐
                                                   │  Google FCM      │
                                                   │  Servers         │
                                                   └──────────────────┘
                                                           │
                                                           │ 4. Silent Push
                                                           │    (no content)
                                                           ▼
┌─────────────┐                                    ┌──────────────┐
│  Device B   │<───────────────────────────────────│ FCM Delivery │
│             │                                    │              │
│ 5. Wake up  │                                    └──────────────┘
│ 6. Fetch &  │
│    Decrypt  │
└─────────────┘
```

**Privacy guarantee:**
- Google sees: "Device B has activity" (timestamp only)
- Google does NOT see: Message content, sender, or recipient

---

## Files Created/Modified

This setup creates or modifies the following files:

### Created:
- `app/google-services.json` (gitignored)
- `supabase/firebase-service-account.json` (gitignored)

### Modified:
- `app/src/main/AndroidManifest.xml` - Added POST_NOTIFICATIONS permission
- `app/src/main/kotlin/com/void/app/MainActivity.kt` - Added permission request
- `.gitignore` - Added google-services.json

---

## Next Steps

Once FCM is working:

1. Test notification delivery on different Android versions
2. Test with screen locked
3. Test with app killed
4. Monitor Edge Function logs for errors
5. Set up Firebase Cloud Functions quota alerts (optional)

---

## Support

If you encounter issues:

1. Check logcat for error messages
2. Verify all files are in correct locations
3. Ensure Supabase secrets are set
4. Check Firebase Console for project configuration

**Common error patterns:**
```bash
# Token not generated
grep "FCM token" logcat.txt

# Registration failed
grep "Push registration failed" logcat.txt

# Permission denied
grep "VOID_PERMISSIONS" logcat.txt
```

---

**Last Updated:** 2026-01-05
**VOID Version:** Phase 4 (FCM Push Notifications)
