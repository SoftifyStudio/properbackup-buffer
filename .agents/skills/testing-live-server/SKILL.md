---
name: testing-properbackup-live
description: Test ProperBackup buffer/web on the live test server (properbackup-test-server.softify.com.pl). Use when deploying changes and verifying E2E on the live environment.
---

# Testing ProperBackup on Live Server

## Devin Secrets Needed

- `TEST_SERVER_SECRET_KEY` — SSH private key (ed25519, base64-encoded with spaces — needs cleanup before use)

## SSH Key Setup

The secret is base64 with embedded spaces. To reconstruct:

```bash
echo "-----BEGIN OPENSSH PRIVATE KEY-----" > /home/ubuntu/.ssh/test_server_key
printf '%s' "${TEST_SERVER_SECRET_KEY}" | tr -d ' \n\r' | fold -w 70 >> /home/ubuntu/.ssh/test_server_key
echo "" >> /home/ubuntu/.ssh/test_server_key
echo "-----END OPENSSH PRIVATE KEY-----" >> /home/ubuntu/.ssh/test_server_key
chmod 600 /home/ubuntu/.ssh/test_server_key
```

## Server Details

- **Host**: `properbackup-test-server.softify.com.pl`
- **SSH**: `ssh -i /home/ubuntu/.ssh/test_server_key root@properbackup-test-server.softify.com.pl`
- **Stack**: Docker Compose at `/opt/properbackup/`
  - `properbackup-db` (PostgreSQL 16-alpine, port 5432 local only)
  - `properbackup-buffer` (eclipse-temurin:21-jre-alpine, port 7100 local only)
  - `properbackup-nginx` (port 80 public)
- **Frontend**: Static files served by nginx
- **Config**: `/opt/properbackup/.env` (DB_PASSWORD, JWT_SECRET, UPLOAD_TOKEN, STRIPE keys)
- **Nginx proxy**: `/api/` → `http://buffer:7100/` (strips `/api` prefix)

## Deploy Procedure

Buffer uses `installDist` (bin/ + lib/ layout), NOT shadow JAR:

```bash
cd /home/ubuntu/repos/properbackup-buffer
./gradlew installDist --no-daemon

# Upload artifacts
scp -i /home/ubuntu/.ssh/test_server_key build/install/properbackup-buffer/lib/*.jar \
  root@properbackup-test-server.softify.com.pl:/opt/properbackup/buffer/lib/
scp -i /home/ubuntu/.ssh/test_server_key build/install/properbackup-buffer/bin/properbackup-buffer \
  root@properbackup-test-server.softify.com.pl:/opt/properbackup/buffer/bin/

# Deploy schema
scp -i /home/ubuntu/.ssh/test_server_key src/main/resources/schema.sql \
  root@properbackup-test-server.softify.com.pl:/opt/properbackup/buffer/schema.sql

# Apply schema (copy into container first)
ssh -i /home/ubuntu/.ssh/test_server_key root@properbackup-test-server.softify.com.pl \
  "docker cp /opt/properbackup/buffer/schema.sql properbackup-db:/tmp/schema.sql && \
   docker exec properbackup-db psql -U properbackup -d properbackup -f /tmp/schema.sql"

# Restart (use force-recreate if .env changed, otherwise restart is enough)
ssh -i /home/ubuntu/.ssh/test_server_key root@properbackup-test-server.softify.com.pl \
  "cd /opt/properbackup && docker compose restart buffer"
```

## IMPORTANT: Env Var Reload

**`docker compose restart` does NOT reload `.env` changes.**

If you changed `/opt/properbackup/.env`, you MUST use:
```bash
cd /opt/properbackup && docker compose up -d --force-recreate buffer
```
This recreates the container with fresh env vars. A plain `restart` keeps the old environment.

## IMPORTANT: Schema Apply Path

The schema.sql file must be copied INTO the Docker container before psql can read it:
```bash
docker cp /opt/properbackup/buffer/schema.sql properbackup-db:/tmp/schema.sql
docker exec properbackup-db psql -U properbackup -d properbackup -f /tmp/schema.sql
```
Do NOT try to reference host paths from inside the container (e.g. `/var/lib/postgresql/data/schema.sql` won't work unless it's a mounted volume).

## Smoke Tests After Deploy

```bash
# Health endpoint
curl -s http://properbackup-test-server.softify.com.pl/api/health
# Expected: "ok"

# Frontend
curl -s -o /dev/null -w '%{http_code}' http://properbackup-test-server.softify.com.pl/
# Expected: 200

# DB alive
ssh -i KEY root@HOST "docker exec properbackup-db pg_isready -U properbackup"
# Expected: "accepting connections"

# No errors in logs
ssh -i KEY root@HOST "docker logs properbackup-buffer --since 2m 2>&1 | grep -i error"
# Expected: empty
```

## Auth for API Testing

Login returns JWT:
```bash
curl -s -X POST http://properbackup-test-server.softify.com.pl/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"USER@EMAIL","password":"PASSWORD"}'
# Returns: {"token":"JWT","email":"...","id":"...","storageLimitBytes":...}
```

Use JWT for authenticated endpoints:
```bash
curl -s http://properbackup-test-server.softify.com.pl/api/account/storage \
  -H "Authorization: Bearer $JWT"
```

## Testing Stripe Webhooks

The webhook endpoint is `POST /api/payment/stripe/webhook` (no JWT, uses Stripe signature).

### Signing Test Payloads

The webhook secret is in the server .env as `STRIPE_TEST_WEBHOOK_SECRET`. Code reads `STRIPE_TEST_WEBHOOK_SECRET` with fallback to `STRIPE_WEBHOOK_SECRET`.

To sign a test payload locally:
```bash
WEBHOOK_SECRET="whsec_YOUR_SECRET_HERE"  # from server .env
BODY='{"id":"evt_test_001","type":"checkout.session.completed",...}'
TIMESTAMP=$(date +%s)
SIGNED_PAYLOAD="${TIMESTAMP}.${BODY}"
SIGNATURE=$(echo -n "$SIGNED_PAYLOAD" | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" | sed 's/.*= //')
HEADER="t=${TIMESTAMP},v1=${SIGNATURE}"

curl -X POST http://properbackup-test-server.softify.com.pl/api/payment/stripe/webhook \
  -H "Content-Type: application/json" \
  -H "Stripe-Signature: $HEADER" \
  -d "$BODY"
```

### Webhook Test Scenarios

1. **Valid webhook** → HTTP 200 "ok", DB mutation applied
2. **Tampered signature** → HTTP 400 "Invalid signature"
3. **Expired timestamp** (>5min old) → HTTP 400 "Invalid signature"
4. **Duplicate event** (same event ID) → HTTP 200 "ok (duplicate)"
5. **Out-of-order event** (stale timestamp < last_stripe_event_at) → HTTP 200 but NO mutation
6. **Blank webhook secret** → HTTP 503 "Webhook secret not configured"

### Dunning Flow Test Scenarios

For testing the dunning email escalation (Group H):

1. **invoice.payment_failed** → sets `subscription_payment_status='past_due'`, `past_due_started_at=NOW()`, audit: `invoice_payment_failed` + `dunning_email_sent marker=past_due_t0`
2. **invoice.paid (recovery)** → clears `subscription_payment_status`, `past_due_started_at`, `last_past_due_notification` to NULL
3. **subscription.deleted** → clears subscription fields + sends final cancellation (audit: `dunning_email_sent marker=final_cancellation`)
4. **COALESCE test** — second `invoice.payment_failed` does NOT overwrite existing `past_due_started_at`

Reset user for dunning tests:
```sql
UPDATE users SET subscription_plan='monthly', stripe_subscription_id='sub_test_dunning_001',
  stripe_customer_id='cus_test_dunning_001', subscription_expires_at=NOW()+INTERVAL '30 days',
  current_period_end=NOW()+INTERVAL '30 days', ever_subscribed=TRUE,
  subscription_payment_status=NULL, past_due_started_at=NULL, last_past_due_notification=NULL,
  cancel_at_period_end=FALSE, last_stripe_event_at=NULL
WHERE id='beab1b48-0ee9-442c-ac3b-c1e654efd7ff';
DELETE FROM stripe_event_idempotency;
DELETE FROM subscription_audit_log WHERE user_id='beab1b48-0ee9-442c-ac3b-c1e654efd7ff';
```

### Test User for Webhooks

Use `beab1b48-0ee9-442c-ac3b-c1e654efd7ff` (e2e-billing-test@properbackup.dev) — trial user suitable for webhook testing.

Basic reset (pre-dunning):
```sql
UPDATE users SET subscription_plan=NULL, stripe_subscription_id=NULL, stripe_customer_id=NULL,
  ever_subscribed=FALSE, subscription_payment_status=NULL, current_period_end=NULL,
  last_stripe_event_at=NULL, cancel_at_period_end=FALSE,
  past_due_started_at=NULL, last_past_due_notification=NULL
WHERE id='beab1b48-0ee9-442c-ac3b-c1e654efd7ff';
DELETE FROM stripe_event_idempotency;
DELETE FROM subscription_audit_log WHERE user_id='beab1b48-0ee9-442c-ac3b-c1e654efd7ff';
```

## Known Issues / Gotchas

1. **Email verification blocks frontend dashboard access** — the deployed frontend enforces email verification. Workaround: test via API with JWT directly, or use pre-existing verified accounts.

2. **Schema uses CREATE IF NOT EXISTS** — if a table already exists with different columns from a prior deploy, the new schema won't overwrite it. Check column lists after deploy. Use ALTER TABLE to fix mismatches manually.

3. **Upload token** is in `/opt/properbackup/.env` as `UPLOAD_TOKEN`. Required for `/upload` endpoint testing.

4. **Agent is actively running** — there are real agents uploading to this server. Be careful with destructive operations (DB drops, container removal). Brief `docker stop properbackup-db` for testing is OK (agents will retry).

5. **No Resend API key configured** — email sending is skipped (logged as SKIP). Verification emails don't actually send. DunningNotifier uses LoggingMailer (stdout) — check `docker logs properbackup-buffer` for email content.

6. **Column name drift** — Prior deploys may have created tables with slightly different column names (e.g. `details` vs `detail`). Always verify with `\d table_name` after deploy if inserts fail.

7. **Env var naming convention** — Buffer reads Stripe keys as `STRIPE_TEST_WEBHOOK_SECRET` (matching docker-compose), with fallback to `STRIPE_WEBHOOK_SECRET`. Both work.

8. **DunningNotifier daemon thread** — starts automatically on buffer boot, checks past_due users hourly. For testing, trigger emails directly via webhook events rather than waiting for the hourly check.

## DB Access

```bash
ssh -i KEY root@HOST "docker exec properbackup-db psql -U properbackup -d properbackup -c 'YOUR_SQL'"
```

## Testing StorageQuotaGuard Fail-Closed

The critical test for the fail-closed fix:
1. Call `/account/storage` with valid JWT → expect 200
2. `docker stop properbackup-db`
3. Call `/account/storage` → expect 500 (NOT 200 with usedBytes=0)
4. `docker start properbackup-db && sleep 3`
5. Call `/account/storage` → expect 200 (recovery)

## Testing SubscriptionGuard State Machine

SubscriptionGuard determines access state from DB columns. To test specific states, manipulate the user's DB record:

| State | DB Setup |
|-------|----------|
| NONE | subscription_plan=NULL, trial_expires_at=NULL |
| TRIALING | trial_expires_at > NOW() |
| ACTIVE | subscription_plan set, subscription_expires_at > NOW() |
| ACTIVE_CANCELLED | same as ACTIVE + cancel_at_period_end=TRUE |
| PAST_DUE_GRACE | same as ACTIVE + subscription_payment_status='past_due' |
| PAST_DUE_SUSPENDED | same as ACTIVE + subscription_payment_status='unpaid' |
| EXPIRED | subscription_expires_at < NOW(), ever_subscribed=TRUE |

Anti-hostage policy: `canRestore = true` in ALL states (GDPR art. 20).
`canUpload = false` only in NONE, PAST_DUE_SUSPENDED, EXPIRED.
