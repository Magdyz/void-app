// =========================================================================
// PHASE 4: FCM PUSH NOTIFICATION EDGE FUNCTION (V1 API)
// =========================================================================
// Triggers on INSERT to message_queue table and sends silent FCM push.
// Push contains only epoch timestamp - NO message content for privacy.
// Uses Firebase Cloud Messaging API V1 (OAuth 2.0)
// =========================================================================

import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

interface MessageQueueInsert {
  record: {
    id: string
    mailbox_hash: string
    ciphertext: string
    epoch: number
    expires_at: string
    created_at: string
  }
}

interface PushRegistration {
  mailbox_hash: string
  fcm_token: string
  expires_at: string
  created_at: string
}

interface FirebaseServiceAccount {
  project_id: string
  private_key: string
  client_email: string
}

// Helper function to get OAuth 2.0 access token
async function getAccessToken(serviceAccount: FirebaseServiceAccount): Promise<string> {
  const jwtHeader = btoa(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))

  const now = Math.floor(Date.now() / 1000)
  const jwtClaimSet = {
    iss: serviceAccount.client_email,
    sub: serviceAccount.client_email,
    aud: 'https://oauth2.googleapis.com/token',
    iat: now,
    exp: now + 3600,
    scope: 'https://www.googleapis.com/auth/firebase.messaging'
  }
  const jwtClaimSetEncoded = btoa(JSON.stringify(jwtClaimSet))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')

  // Sign JWT with private key
  const signatureInput = `${jwtHeader}.${jwtClaimSetEncoded}`
  const key = await crypto.subtle.importKey(
    'pkcs8',
    pemToArrayBuffer(serviceAccount.private_key),
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign']
  )
  const signature = await crypto.subtle.sign(
    'RSASSA-PKCS1-v1_5',
    key,
    new TextEncoder().encode(signatureInput)
  )
  const signatureEncoded = btoa(String.fromCharCode(...new Uint8Array(signature)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')

  const jwt = `${signatureInput}.${signatureEncoded}`

  // Exchange JWT for access token
  const tokenResponse = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: jwt
    })
  })

  const tokenData = await tokenResponse.json()
  return tokenData.access_token
}

// Convert PEM private key to ArrayBuffer
function pemToArrayBuffer(pem: string): ArrayBuffer {
  const pemContents = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, '')
    .replace(/-----END PRIVATE KEY-----/, '')
    .replace(/\s/g, '')
  const binaryString = atob(pemContents)
  const bytes = new Uint8Array(binaryString.length)
  for (let i = 0; i < binaryString.length; i++) {
    bytes[i] = binaryString.charCodeAt(i)
  }
  return bytes.buffer
}

Deno.serve(async (req) => {
  try {
    // Parse the webhook payload from database INSERT trigger
    const payload: MessageQueueInsert = await req.json()
    const { mailbox_hash, epoch } = payload.record

    console.log(`New message for mailbox: ${mailbox_hash.substring(0, 8)}... at epoch: ${epoch}`)

    // Create Supabase client with service role key (bypasses RLS)
    const supabaseUrl = Deno.env.get('SUPABASE_URL')!
    const supabaseServiceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!
    const supabase = createClient(supabaseUrl, supabaseServiceKey)

    // Look up FCM token for this mailbox
    const { data: registrations, error: lookupError } = await supabase
      .from('push_registrations')
      .select('fcm_token')
      .eq('mailbox_hash', mailbox_hash)
      .single()

    if (lookupError || !registrations) {
      // No push registration found - user disabled push or hasn't registered yet
      // This is normal - client will poll instead
      console.log(`No FCM token for mailbox ${mailbox_hash.substring(0, 8)}... - skipping push`)
      return new Response(JSON.stringify({ skipped: true, reason: 'no_fcm_token' }), {
        headers: { 'Content-Type': 'application/json' },
        status: 200
      })
    }

    const fcmToken = (registrations as PushRegistration).fcm_token

    // Get Firebase service account from environment
    const serviceAccountJson = Deno.env.get('FIREBASE_SERVICE_ACCOUNT')!
    const serviceAccount: FirebaseServiceAccount = JSON.parse(serviceAccountJson)

    // Get OAuth 2.0 access token
    const accessToken = await getAccessToken(serviceAccount)

    // Generate random nonce to ensure iOS doesn't deduplicate identical pushes
    const nonce = crypto.randomUUID()

    // Send silent FCM push notification using V1 API
    const fcmPayload = {
      message: {
        token: fcmToken,
        data: {
          epoch: epoch.toString(),
          nonce: nonce
        },
        android: {
          priority: 'high'
        },
        apns: {
          headers: {
            'apns-priority': '10'
          },
          payload: {
            aps: {
              'content-available': 1
            }
          }
        }
      }
    }

    const fcmResponse = await fetch(
      `https://fcm.googleapis.com/v1/projects/${serviceAccount.project_id}/messages:send`,
      {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${accessToken}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(fcmPayload)
      }
    )

    const fcmResult = await fcmResponse.json()

    if (!fcmResponse.ok) {
      console.error('FCM push failed:', fcmResult)
      return new Response(JSON.stringify({ error: 'fcm_failed', details: fcmResult }), {
        headers: { 'Content-Type': 'application/json' },
        status: 500
      })
    }

    console.log(`Push sent successfully to ${mailbox_hash.substring(0, 8)}...`)
    return new Response(JSON.stringify({ success: true, fcm_result: fcmResult }), {
      headers: { 'Content-Type': 'application/json' },
      status: 200
    })

  } catch (error) {
    console.error('Error in send-push-notification function:', error)
    return new Response(JSON.stringify({ error: error.message }), {
      headers: { 'Content-Type': 'application/json' },
      status: 500
    })
  }
})

// =========================================================================
// ENVIRONMENT VARIABLES REQUIRED
// =========================================================================
// Set these in Supabase Dashboard > Edge Functions > Settings:
// - SUPABASE_URL: Your Supabase project URL (auto-set by Supabase)
// - SUPABASE_SERVICE_ROLE_KEY: Service role key (auto-set by Supabase)
// - FIREBASE_SERVICE_ACCOUNT: Your Firebase service account JSON (entire file as string)

// =========================================================================
// IMPORTANT SECURITY NOTES
// =========================================================================
// 1. NEVER include message content (ciphertext) in the push notification
// 2. Only send epoch timestamp and random nonce
// 3. Silent push only - no notification UI
// 4. Server cannot determine who sent or received the message
// 5. FCM token is temporary (expires every 25 hours with mailbox rotation)
