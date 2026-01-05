# Why VOID Uses FCM

VOID's security model relies on **Poisson Ghost Protocol** - a cryptographic timing obfuscation system that requires constant background wake-ups.

## Why Not Polling?

Polling-based approaches (no FCM) cannot provide pattern obfuscation because:

1. **Android Doze prevents background polling** when device is idle
2. **Polling only occurs when app is active**, revealing usage patterns
3. **Battery drain from constant polling** is worse than FCM + heartbeats

## What About Google Privacy?

### FCM sees:
- Your device receives notifications every 10-20 minutes (Poisson distribution)
- All notifications are identical (heartbeats + messages are indistinguishable)

### FCM cannot see:
- Which notifications contain real messages
- How many messages you receive
- Who you're communicating with
- Your communication patterns

This is **more private than Signal** (which shows "Signal message" notifications) and **as private as zero-Google approaches** for traffic analysis.

## Trust Model

### You must trust:
- ✅ Your device (you install the app)
- ✅ VOID servers (we publish audits)
- ✅ End-to-end encryption (open source, audited)
- ✅ Poisson timing mathematics (peer-reviewed)
- ⚠️ Google FCM infrastructure (for delivery only, not content)

### You do NOT trust:
- ❌ Google to see message content (encrypted)
- ❌ Google to see metadata (obfuscated by heartbeats)
- ❌ VOID servers to see message content (sealed sender)

## Technical Implementation

VOID uses FCM exclusively for:
1. **Wake-up signals** - triggering the app to check for messages
2. **Poisson heartbeats** - maintaining timing obfuscation 24/7
3. **Battery efficiency** - leveraging Android's optimized push infrastructure

All message content is:
- End-to-end encrypted before leaving your device
- Never visible to FCM or Google
- Protected by sealed sender (VOID servers can't see who messages whom)

## Why This Matters

Without constant background wake-ups, adversaries can perform **traffic analysis** by observing when your app becomes active. The Poisson Ghost Protocol eliminates this attack vector by ensuring your device activity is cryptographically indistinguishable from random noise.

**This is VOID's core innovation** - making metadata resistance practical on mobile devices.
