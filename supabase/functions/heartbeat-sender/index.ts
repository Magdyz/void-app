// =========================================================================
// POISSON GHOST PROTOCOL: HEARTBEAT SENDER EDGE FUNCTION
// =========================================================================
// Sends periodic "heartbeat" FCM pushes at random intervals (10-20 min).
// This creates constant background traffic indistinguishable from real messages.
//
// Privacy Architecture:
// - Heartbeat pushes look identical to real message notifications
// - Random intervals per user (Poisson distribution)
// - Client always fetches mailbox on heartbeat (even if empty)
// - Server always returns 2KB (padded messages or noise)
// - Google/ISP cannot distinguish heartbeat from real message
//
// Triggered by: pg_cron every 1 minute
// =========================================================================

import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

interface DueHeartbeat {
  mailbox_hash: string
  fcm_token: string
  heartbeat_interval_seconds: number
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
    console.log('🫀 Heartbeat sender triggered')

    // Create Supabase client with service role key (bypasses RLS)
    const supabaseUrl = Deno.env.get('SUPABASE_URL')!
    const supabaseServiceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!
    const supabase = createClient(supabaseUrl, supabaseServiceKey)

    // Get users due for heartbeat
    const { data: dueHeartbeats, error: queryError } = await supabase
      .rpc('get_due_heartbeats')

    if (queryError) {
      console.error('❌ Failed to query due heartbeats:', queryError)
      return new Response(JSON.stringify({ error: 'query_failed', details: queryError }), {
        headers: { 'Content-Type': 'application/json' },
        status: 500
      })
    }

    const heartbeats = dueHeartbeats as DueHeartbeat[]

    if (!heartbeats || heartbeats.length === 0) {
      console.log('✓ No heartbeats due at this time')
      return new Response(JSON.stringify({ sent: 0, message: 'no_heartbeats_due' }), {
        headers: { 'Content-Type': 'application/json' },
        status: 200
      })
    }

    console.log(`📨 Sending heartbeats to ${heartbeats.length} user(s)`)

    // Get Firebase service account from environment
    const serviceAccountJson = Deno.env.get('FIREBASE_SERVICE_ACCOUNT')!
    const serviceAccount: FirebaseServiceAccount = JSON.parse(serviceAccountJson)

    // Get OAuth 2.0 access token (reused for all heartbeats in this batch)
    const accessToken = await getAccessToken(serviceAccount)

    // Send heartbeats to all due users
    let successCount = 0
    let failureCount = 0

    for (const heartbeat of heartbeats) {
      try {
        // Generate random nonce to ensure iOS doesn't deduplicate identical pushes
        const nonce = crypto.randomUUID()
        const epoch = Math.floor(Date.now() / 1000)

        // Send silent FCM push notification
        // IMPORTANT: This looks identical to real message notifications
        // The client MUST always fetch mailbox on receipt
        const fcmPayload = {
          message: {
            token: heartbeat.fcm_token,
            data: {
              type: 'heartbeat', // Distinguishes heartbeat from message notification
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
          console.error(`❌ FCM failed for ${heartbeat.mailbox_hash.substring(0, 8)}:`, fcmResult)
          failureCount++
          continue
        }

        // Update next heartbeat time
        const { error: updateError } = await supabase
          .rpc('update_next_heartbeat', { p_mailbox_hash: heartbeat.mailbox_hash })

        if (updateError) {
          console.error(`❌ Failed to update next heartbeat for ${heartbeat.mailbox_hash.substring(0, 8)}:`, updateError)
          // Don't increment failure count - FCM succeeded, this is just scheduling
        }

        successCount++
        console.log(`✓ Heartbeat sent to ${heartbeat.mailbox_hash.substring(0, 8)}...`)

      } catch (error) {
        console.error(`❌ Error sending heartbeat to ${heartbeat.mailbox_hash.substring(0, 8)}:`, error)
        failureCount++
      }
    }

    console.log(`✅ Heartbeat batch complete: ${successCount} sent, ${failureCount} failed`)

    return new Response(JSON.stringify({
      success: true,
      sent: successCount,
      failed: failureCount,
      total: heartbeats.length
    }), {
      headers: { 'Content-Type': 'application/json' },
      status: 200
    })

  } catch (error) {
    console.error('❌ Error in heartbeat-sender function:', error)
    return new Response(JSON.stringify({ error: error.message }), {
      headers: { 'Content-Type': 'application/json' },
      status: 500
    })
  }
})

// =========================================================================
// ENVIRONMENT VARIABLES REQUIRED
// =========================================================================
// Same as send-push-notification:
// - SUPABASE_URL: Your Supabase project URL (auto-set by Supabase)
// - SUPABASE_SERVICE_ROLE_KEY: Service role key (auto-set by Supabase)
// - FIREBASE_SERVICE_ACCOUNT: Your Firebase service account JSON (entire file as string)

// =========================================================================
// DEPLOYMENT
// =========================================================================
// 1. Deploy this function: supabase functions deploy heartbeat-sender
// 2. This function is invoked by pg_cron (not by HTTP webhook)
// 3. Set up cron job in migration 13_poisson_ghost_scheduler.sql

// =========================================================================
// IMPORTANT PRIVACY NOTES
// =========================================================================
// 1. Heartbeat pushes are indistinguishable from real message notifications
// 2. Google sees: random FCM pushes at varying intervals (looks like normal messaging)
// 3. Client always fetches mailbox (even if empty) - creates constant 2KB traffic
// 4. Server cannot determine if user has pending messages without client fetching
// 5. This creates "noise floor" that hides real communication patterns
