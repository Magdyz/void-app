// =========================================================================
// POISSON GHOST PROTOCOL: FETCH MAILBOX EDGE FUNCTION
// =========================================================================
// Fetches messages from blind mailbox with constant 4KB response size.
//
// Privacy Architecture:
// - ALWAYS returns exactly 4KB (4096 bytes)
// - Real messages: padded to 4KB with random bytes
// - Empty mailbox: 4KB of random noise
// - ISP/Google cannot determine if response contains real messages
// - Response size is constant regardless of message count/size
//
// Response Format:
// - Byte 0: Magic byte (0x01 = real messages, 0x00 = noise)
// - Byte 1-4095: Message data (JSON) + padding, OR pure noise
//
// Authentication:
// - Uses ephemeral tokens (X-Mailbox-Token header)
// - Same RLS validation as direct Postgrest queries
// - Token must match mailbox_hash and be non-expired
// =========================================================================

import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

interface MessageRecord {
  id: string
  mailbox_hash: string
  ciphertext: string
  epoch: number
  expires_at: string
  created_at: string
}

interface FetchRequest {
  mailbox_hash: string
  epoch: number
  epoch_window?: number
}

// Constants
const RESPONSE_SIZE = 4096 // Always 4KB
const MAGIC_BYTE_REAL = 0x01 // Real messages present
const MAGIC_BYTE_NOISE = 0x00 // No messages (noise response)
const DEFAULT_EPOCH_WINDOW = 3600 // ±1 hour for clock skew

Deno.serve(async (req) => {
  try {
    // Only allow POST requests
    if (req.method !== 'POST') {
      console.error('❌ Method not allowed:', req.method)
      return generateNoiseResponse() // Return noise for privacy
    }

    // Parse request body
    const body: FetchRequest = await req.json()
    const { mailbox_hash, epoch, epoch_window = DEFAULT_EPOCH_WINDOW } = body

    // Validate mailbox_hash format
    if (!mailbox_hash || mailbox_hash.length !== 64) {
      console.error('❌ Invalid mailbox_hash format')
      return generateNoiseResponse() // Return noise instead of error for privacy
    }

    // Get ephemeral token from header
    const token = req.headers.get('X-Mailbox-Token')
    if (!token) {
      console.error('❌ Missing X-Mailbox-Token header')
      return generateNoiseResponse() // Return noise instead of error for privacy
    }

    // Create Supabase client with service role key (bypasses RLS)
    const supabaseUrl = Deno.env.get('SUPABASE_URL')!
    const supabaseServiceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!
    const supabase = createClient(supabaseUrl, supabaseServiceKey)

    // Validate ephemeral token
    const { data: tokenData, error: tokenError } = await supabase
      .from('ephemeral_tokens')
      .select('mailbox_hash, expires_at')
      .eq('id', token)
      .single()

    if (tokenError || !tokenData) {
      console.error('❌ Invalid token:', tokenError)
      return generateNoiseResponse() // Return noise instead of error for privacy
    }

    // Verify token matches requested mailbox
    if (tokenData.mailbox_hash !== mailbox_hash) {
      console.error('❌ Token mailbox mismatch')
      return generateNoiseResponse() // Return noise instead of error for privacy
    }

    // Verify token is not expired
    const tokenExpiry = new Date(tokenData.expires_at)
    if (tokenExpiry < new Date()) {
      console.error('❌ Token expired')
      return generateNoiseResponse() // Return noise instead of error for privacy
    }

    console.log(`📥 Fetching messages for mailbox ${mailbox_hash.substring(0, 8)}...`)

    // Fetch messages from message_queue
    const epochMin = epoch - epoch_window
    const epochMax = epoch + epoch_window

    const { data: messages, error: queryError } = await supabase
      .from('message_queue')
      .select('*')
      .eq('mailbox_hash', mailbox_hash)
      .gte('epoch', epochMin)
      .lte('epoch', epochMax)

    if (queryError) {
      console.error('❌ Query error:', queryError)
      return generateNoiseResponse() // Return noise instead of error for privacy
    }

    // Check if messages exist
    const messageList = messages as MessageRecord[]
    const hasMessages = messageList && messageList.length > 0

    if (hasMessages) {
      console.log(`✓ Found ${messageList.length} message(s) - returning padded response`)
      return generateMessageResponse(messageList)
    } else {
      console.log(`○ No messages found - returning noise response`)
      return generateNoiseResponse()
    }

  } catch (error) {
    console.error('❌ Error in fetch-mailbox function:', error)
    // Return noise instead of error response for privacy
    return generateNoiseResponse()
  }
})

/**
 * Generate response with real messages (padded to 4KB).
 *
 * Format:
 * - Byte 0: 0x01 (magic byte - real messages)
 * - Byte 1-N: JSON serialized messages
 * - Byte N+1 to 4095: Random padding
 *
 * IMPORTANT: This function implements smart batching to prevent JSON truncation.
 * It only includes messages that fit within the 4KB limit, ensuring valid JSON.
 * Remaining messages will be fetched in subsequent polls.
 */
function generateMessageResponse(messages: MessageRecord[]): Response {
  const maxDataSize = RESPONSE_SIZE - 1 // Reserve 1 byte for magic byte

  // Build response incrementally, only including messages that fit
  const fittingMessages: MessageRecord[] = []

  for (let i = 0; i < messages.length; i++) {
    // Try adding this message to the batch
    const testBatch = [...fittingMessages, messages[i]]
    const testJson = JSON.stringify({ messages: testBatch })
    const testBytes = new TextEncoder().encode(testJson)

    if (testBytes.length > maxDataSize) {
      // This message doesn't fit, stop here
      console.log(`⚠️  Message ${i + 1}/${messages.length} doesn't fit (would be ${testBytes.length} bytes)`)
      console.log(`📦 Returning ${fittingMessages.length}/${messages.length} messages in this batch`)
      break
    }

    // Message fits, add it to the batch
    fittingMessages.push(messages[i])
  }

  // If no messages fit at all, return the first message anyway (truncated)
  // This is a fallback for extremely large single messages
  if (fittingMessages.length === 0 && messages.length > 0) {
    console.error(`⚠️  WARNING: First message is too large for 4KB response, truncating`)
    const singleMessage = JSON.stringify({ messages: [messages[0]] })
    const truncated = new TextEncoder().encode(singleMessage).slice(0, maxDataSize)
    const response = new Uint8Array(RESPONSE_SIZE)
    response[0] = MAGIC_BYTE_REAL
    response.set(truncated, 1)

    // Fill remaining with random padding
    const paddingStart = 1 + truncated.length
    const padding = new Uint8Array(RESPONSE_SIZE - paddingStart)
    crypto.getRandomValues(padding)
    response.set(padding, paddingStart)

    return new Response(response, {
      headers: {
        'Content-Type': 'application/octet-stream',
        'Content-Length': RESPONSE_SIZE.toString()
      },
      status: 200
    })
  }

  // Serialize the fitting messages
  const messageJson = JSON.stringify({ messages: fittingMessages })
  const messageBytes = new TextEncoder().encode(messageJson)

  // Create 2KB response buffer
  const response = new Uint8Array(RESPONSE_SIZE)

  // Byte 0: Magic byte (0x01 = real messages)
  response[0] = MAGIC_BYTE_REAL

  // Bytes 1 to messageBytes.length: Message data
  response.set(messageBytes, 1)

  // Remaining bytes: Random padding
  const paddingStart = 1 + messageBytes.length
  const paddingLength = RESPONSE_SIZE - paddingStart
  const padding = new Uint8Array(paddingLength)
  crypto.getRandomValues(padding)
  response.set(padding, paddingStart)

  console.log(`📦 Response: ${fittingMessages.length} message(s), ${messageBytes.length} bytes data + ${paddingLength} bytes padding = ${RESPONSE_SIZE} bytes total`)

  if (fittingMessages.length < messages.length) {
    console.log(`ℹ️  ${messages.length - fittingMessages.length} message(s) remaining for next fetch`)
  }

  return new Response(response, {
    headers: {
      'Content-Type': 'application/octet-stream',
      'Content-Length': RESPONSE_SIZE.toString()
    },
    status: 200
  })
}

/**
 * Generate noise response (4KB of random data).
 *
 * Format:
 * - Byte 0: 0x00 (magic byte - noise/no messages)
 * - Byte 1-4095: Random noise
 */
function generateNoiseResponse(): Response {
  const response = new Uint8Array(RESPONSE_SIZE)

  // Byte 0: Magic byte (0x00 = noise)
  response[0] = MAGIC_BYTE_NOISE

  // Bytes 1-2047: Random noise
  const noise = new Uint8Array(RESPONSE_SIZE - 1)
  crypto.getRandomValues(noise)
  response.set(noise, 1)

  console.log(`🎭 Noise response: ${RESPONSE_SIZE} bytes random data`)

  return new Response(response, {
    headers: {
      'Content-Type': 'application/octet-stream',
      'Content-Length': RESPONSE_SIZE.toString()
    },
    status: 200
  })
}

// =========================================================================
// ENVIRONMENT VARIABLES REQUIRED
// =========================================================================
// - SUPABASE_URL: Your Supabase project URL (auto-set by Supabase)
// - SUPABASE_SERVICE_ROLE_KEY: Service role key (auto-set by Supabase)

// =========================================================================
// CLIENT USAGE
// =========================================================================
// POST /fetch-mailbox
// Headers:
//   X-Mailbox-Token: <ephemeral_token_id>
//   Content-Type: application/json
// Body:
//   {
//     "mailbox_hash": "64-char-hex-string",
//     "epoch": 1234567890,
//     "epoch_window": 3600  // Optional, defaults to 3600
//   }
//
// Response:
//   Binary data (4096 bytes)
//   Byte 0: 0x01 = real messages, 0x00 = noise
//   Byte 1+: Message JSON + padding, OR noise

// =========================================================================
// IMPORTANT PRIVACY NOTES
// =========================================================================
// 1. Response is ALWAYS exactly 4KB - ISP cannot determine message presence
// 2. Error responses return noise instead of HTTP errors for privacy
// 3. Client MUST check magic byte to distinguish real messages from noise
// 4. Token validation happens server-side (same security as direct Postgrest)
// 5. Random padding makes each response unique (prevents deduplication)
