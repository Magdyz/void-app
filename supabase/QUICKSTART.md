# Supabase Project Setup - Quick Start

## 1. Create Supabase Project

1. Go to https://supabase.com
2. Sign up/Login
3. Click "New Project"
4. Fill in:
   - **Name**: `void-app` (or your preferred name)
   - **Database Password**: Generate strong password (save it securely)
   - **Region**: Choose closest to your users
   - **Plan**: Free tier is sufficient for development
5. Click "Create new project"
6. Wait 2-3 minutes for project initialization

## 2. Get Supabase Secrets

Once project is ready:

### Project URL and Keys
1. Go to **Project Settings** (gear icon) → **API**
2. Copy and save these values:

```
SUPABASE_URL=https://[your-project-ref].supabase.co
SUPABASE_ANON_KEY=[your-anon-key]
SUPABASE_SERVICE_ROLE_KEY=[your-service-role-key]  # Keep secret!
```

### Project Reference ID
1. From **Project Settings** → **General**
2. Copy **Reference ID**: `[your-project-ref]`

## 3. Get Firebase Service Account

1. Go to https://console.firebase.google.com
2. Select your Firebase project (or create one)
3. Click gear icon → **Project Settings**
4. Go to **Service Accounts** tab
5. Click **Generate new private key**
6. Download the JSON file (e.g., `firebase-service-account.json`)
7. **Keep this file secure - it contains your private key!**

```json
{
  "type": "service_account",
  "project_id": "your-project-id",
  "private_key_id": "...",
  "private_key": "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----\n",
  "client_email": "firebase-adminsdk-...@your-project-id.iam.gserviceaccount.com",
  ...
}
```

## 4. Set Secrets in Supabase

### Install Supabase CLI
```bash
# macOS
brew install supabase/tap/supabase

# Other platforms: see https://supabase.com/docs/guides/cli/getting-started
```

### Link to your project
```bash
cd /Users/magz/Documents/Coding/void-app
supabase login
supabase link --project-ref [your-project-ref]
```

### Set Firebase service account secret
```bash
cd /Users/magz/Documents/Coding/void-app
supabase secrets set FIREBASE_SERVICE_ACCOUNT="$(cat supabase/firebase-service-account.json)"
```

**Important**: This stores the entire JSON file content as a secret in Supabase.

## 5. Run Migrations

### Option A: Via Supabase Dashboard
1. Go to **SQL Editor** in Supabase Dashboard
2. Copy contents from `migrations/01_message_queue.sql`
3. Paste and click **Run**
4. Repeat for `02` through `06` in order

### Option B: Via CLI (if initialized)
```bash
supabase db push
```

## 6. Deploy Edge Function

```bash
supabase functions deploy send-push-notification
```

## 7. Configure Database Webhook

1. Go to **Database** → **Webhooks** → **Create a new hook**
2. Configure:
   - **Name**: `push-notification-on-message-insert`
   - **Table**: `message_queue`
   - **Events**: Check `INSERT`
   - **Type**: `HTTP Request`
   - **Method**: `POST`
   - **URL**: `https://[your-project-ref].supabase.co/functions/v1/send-push-notification`
   - **HTTP Headers**:
     ```
     Authorization: Bearer [SUPABASE_ANON_KEY]
     Content-Type: application/json
     ```
3. Click **Create webhook**

## 8. Update Android App

Add to your app's configuration (e.g., `local.properties` or secure config):

```properties
SUPABASE_URL=https://[your-project-ref].supabase.co
SUPABASE_ANON_KEY=[your-anon-key]
```

**Never commit `SUPABASE_SERVICE_ROLE_KEY` to your Android app - it's server-side only!**

---

**Next**: See `SETUP_INSTRUCTIONS.md` for detailed testing, client SDK integration, and troubleshooting.
