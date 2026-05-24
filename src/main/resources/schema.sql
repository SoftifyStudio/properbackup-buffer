CREATE TABLE IF NOT EXISTS users (
  id                 VARCHAR(36)  PRIMARY KEY,
  email              VARCHAR(255) UNIQUE NOT NULL,
  bcrypt_hash        VARCHAR(255) NOT NULL,
  created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  is_system_admin    BOOLEAN      NOT NULL DEFAULT FALSE
);

ALTER TABLE users ADD COLUMN IF NOT EXISTS is_system_admin BOOLEAN NOT NULL DEFAULT FALSE;

-- Trial / subscription columns
ALTER TABLE users ADD COLUMN IF NOT EXISTS trial_expires_at  TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS subscription_plan VARCHAR(32);
ALTER TABLE users ADD COLUMN IF NOT EXISTS subscription_expires_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS subscription_cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS storage_limit_bytes BIGINT NOT NULL DEFAULT 2199023255552;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_trial_notification VARCHAR(16);

-- Set trial_expires_at for existing users if NULL (30 days from creation)
UPDATE users SET trial_expires_at = created_at + INTERVAL '30 days' WHERE trial_expires_at IS NULL AND subscription_plan IS NULL;

CREATE TABLE IF NOT EXISTS servers (
  id           VARCHAR(36)  PRIMARY KEY,
  name         VARCHAR(255) NOT NULL,
  user_id      VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  activated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  last_seen_at TIMESTAMPTZ
);
ALTER TABLE servers ALTER COLUMN last_seen_at DROP NOT NULL;

CREATE TABLE IF NOT EXISTS activation_tokens (
  token      VARCHAR(16)  PRIMARY KEY,
  user_id    VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at TIMESTAMPTZ  NOT NULL,
  used       BOOLEAN      NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  server_id  VARCHAR(36)  REFERENCES servers(id) ON DELETE SET NULL
);
ALTER TABLE activation_tokens ADD COLUMN IF NOT EXISTS server_id VARCHAR(36) REFERENCES servers(id) ON DELETE SET NULL;

-- Indeks ścieżek (path_id → ścieżka pliku/folderu) — źródło prawdy dla panelu i /servers/{id}/paths.idx.
-- Zasilany przy każdym zapisanym pakiecie (flush). Ta sama baza co users/servers/logi.
CREATE TABLE IF NOT EXISTS paths_idx (
  path_id       VARCHAR(8)  NOT NULL,
  server_id     VARCHAR(36) NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
  original_path TEXT        NOT NULL,
  registered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (path_id, server_id)
);

CREATE INDEX IF NOT EXISTS idx_paths_idx_server_id ON paths_idx (server_id);

CREATE TABLE IF NOT EXISTS flush_budget (
  user_id    VARCHAR(64) NOT NULL,
  path_id    VARCHAR(32) NOT NULL,
  day        DATE        NOT NULL DEFAULT CURRENT_DATE,
  used_count INT         NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, path_id, day)
);

CREATE TABLE IF NOT EXISTS service_admin_codes (
  id           BIGSERIAL PRIMARY KEY,
  code_hash    VARCHAR(255) NOT NULL,
  label        VARCHAR(128) NOT NULL DEFAULT 'seed',
  one_time     BOOLEAN      NOT NULL DEFAULT FALSE,
  used_at      TIMESTAMPTZ,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  expires_at   TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS stack_logs (
  id           BIGSERIAL PRIMARY KEY,
  machine_id   VARCHAR(128) NOT NULL,
  component    VARCHAR(32)  NOT NULL,
  event_code   INT          NOT NULL,
  level        VARCHAR(16)  NOT NULL DEFAULT 'INFO',
  message      TEXT         NOT NULL,
  meta_json    TEXT         NOT NULL DEFAULT '{}',
  dropped      INT          NOT NULL DEFAULT 0,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_stack_logs_machine_time ON stack_logs(machine_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_stack_logs_component_time ON stack_logs(component, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_stack_logs_event_code ON stack_logs(event_code);

CREATE TABLE IF NOT EXISTS machine_file_state (
  machine_id   VARCHAR(128) NOT NULL,
  full_path    TEXT         NOT NULL,
  status       VARCHAR(32)  NOT NULL,
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  PRIMARY KEY (machine_id, full_path)
);

CREATE INDEX IF NOT EXISTS idx_machine_file_state_machine_time ON machine_file_state(machine_id, updated_at DESC);

-- Append-only historia zmian statusu pliku (tylko realne przejścia, bez powtórzeń tego samego statusu)
CREATE TABLE IF NOT EXISTS machine_file_event (
  id               BIGSERIAL PRIMARY KEY,
  machine_id       VARCHAR(128) NOT NULL,
  full_path        TEXT         NOT NULL,
  status           VARCHAR(32)  NOT NULL,
  previous_status  VARCHAR(32),
  occurred_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_mfe_machine_path_time ON machine_file_event(machine_id, full_path, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_mfe_machine_status_time ON machine_file_event(machine_id, status, occurred_at DESC);

-- Paczka archiwum po seal (nazwa obiektu znana wyłącznie po stronie buffera)
CREATE TABLE IF NOT EXISTS archive_snapshot (
  id           BIGSERIAL PRIMARY KEY,
  server_id    VARCHAR(36) NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
  path_id      VARCHAR(8)  NOT NULL,
  object_name  TEXT        NOT NULL,
  flag         CHAR(1)     NOT NULL,
  size_bytes   BIGINT      NOT NULL,
  sealed_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (server_id, object_name)
);

CREATE INDEX IF NOT EXISTS idx_archive_snapshot_server_path_time ON archive_snapshot(server_id, path_id, sealed_at DESC);

-- Skonfigurowane rooty backupu (agent → panel od razu po instalacji)
CREATE TABLE IF NOT EXISTS machine_backup_root (
  machine_id   VARCHAR(128) NOT NULL,
  path_id      VARCHAR(8)    NOT NULL,
  root_path    TEXT          NOT NULL,
  updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  PRIMARY KEY (machine_id, path_id)
);

CREATE INDEX IF NOT EXISTS idx_mbr_machine ON machine_backup_root(machine_id);

-- Ostatni znany stan agenta (jak Spotlight: indeksowanie / bezczynność)
CREATE TABLE IF NOT EXISTS machine_agent_status (
  machine_id   VARCHAR(128) PRIMARY KEY,
  phase        VARCHAR(32)  NOT NULL,
  detail       TEXT,
  total_files   INT,
  scanned_files INT,
  current_file  TEXT,
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

ALTER TABLE machine_agent_status ADD COLUMN IF NOT EXISTS total_files   INT;
ALTER TABLE machine_agent_status ADD COLUMN IF NOT EXISTS scanned_files INT;
ALTER TABLE machine_agent_status ADD COLUMN IF NOT EXISTS current_file  TEXT;

CREATE INDEX IF NOT EXISTS idx_mas_updated ON machine_agent_status(updated_at DESC);

-- Paths configured from web panel (user sets which folders to back up)
CREATE TABLE IF NOT EXISTS server_backup_config (
  server_id    VARCHAR(36)  NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
  folder_path  TEXT         NOT NULL,
  enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  PRIMARY KEY (server_id, folder_path)
);

CREATE INDEX IF NOT EXISTS idx_sbc_server ON server_backup_config(server_id);

-- Auto-test restore verification results
CREATE TABLE IF NOT EXISTS verification_result (
  server_id    VARCHAR(36)  NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
  object_name  TEXT         NOT NULL,
  path_id      TEXT         NOT NULL DEFAULT '',
  status       VARCHAR(16)  NOT NULL DEFAULT 'pending',
  detail       TEXT,
  verified_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  PRIMARY KEY (server_id, object_name)
);

CREATE INDEX IF NOT EXISTS idx_vr_server_time ON verification_result(server_id, verified_at DESC);

-- Agent resource metrics (CPU / RAM) — time-series per server
CREATE TABLE IF NOT EXISTS agent_metrics (
  id           BIGSERIAL PRIMARY KEY,
  server_id    VARCHAR(36)  NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
  cpu_percent  REAL         NOT NULL DEFAULT 0,
  ram_used_mb  REAL         NOT NULL DEFAULT 0,
  ram_total_mb REAL         NOT NULL DEFAULT 0,
  recorded_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

ALTER TABLE agent_metrics ADD COLUMN IF NOT EXISTS core_count INT NOT NULL DEFAULT 1;
ALTER TABLE agent_metrics ADD COLUMN IF NOT EXISTS per_core_cpu JSONB;

CREATE INDEX IF NOT EXISTS idx_agent_metrics_server_time ON agent_metrics(server_id, recorded_at DESC);

-- Buffer packs — chunks collected into packs before flushing to remote storage
CREATE TABLE IF NOT EXISTS buffer_pack (
  id           BIGSERIAL PRIMARY KEY,
  server_id    VARCHAR(36)  NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
  pack_name    TEXT         NOT NULL,
  size_bytes   BIGINT       NOT NULL,
  flushed_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  UNIQUE (server_id, pack_name)
);

CREATE INDEX IF NOT EXISTS idx_buffer_pack_server_time ON buffer_pack(server_id, flushed_at DESC);

-- ============================================================================
-- Data retention / cleanup support
-- ============================================================================

-- Retention config per table (configurable from admin panel)
CREATE TABLE IF NOT EXISTS data_retention_config (
  table_name   VARCHAR(64) PRIMARY KEY,
  retention_days INT NOT NULL DEFAULT 30,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Default retention: 30 days for metrics, 90 days for logs/events
INSERT INTO data_retention_config (table_name, retention_days) VALUES
  ('agent_metrics', 30),
  ('stack_logs', 90),
  ('machine_file_event', 90)
ON CONFLICT (table_name) DO NOTHING;

-- Stale backup notification tracking
ALTER TABLE servers ADD COLUMN IF NOT EXISTS stale_notified_at TIMESTAMPTZ;

-- Email verification
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verify_token VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verify_expires_at TIMESTAMPTZ;

-- HotPay payment tracking
CREATE TABLE IF NOT EXISTS payment_order (
  id               VARCHAR(64) PRIMARY KEY,
  user_id          VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  plan             VARCHAR(32) NOT NULL,
  amount_pln       NUMERIC(10,2) NOT NULL,
  status           VARCHAR(16) NOT NULL DEFAULT 'pending',
  hotpay_payment_id VARCHAR(64),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_payment_order_user ON payment_order(user_id, created_at DESC);

-- 950MB chunk-to-snapshot mapping (Task D)
CREATE TABLE IF NOT EXISTS archive_chunk (
  id              BIGSERIAL PRIMARY KEY,
  server_id       VARCHAR(36)  NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
  snapshot_id     BIGINT       REFERENCES archive_snapshot(id) ON DELETE SET NULL,
  chunk_name      TEXT         NOT NULL,
  chunk_index     INT          NOT NULL DEFAULT 0,
  size_bytes      BIGINT       NOT NULL DEFAULT 0,
  path_id         VARCHAR(8),
  original_paths  TEXT,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  UNIQUE (server_id, chunk_name)
);

CREATE INDEX IF NOT EXISTS idx_archive_chunk_server_snapshot ON archive_chunk(server_id, snapshot_id);
CREATE INDEX IF NOT EXISTS idx_archive_chunk_server_time ON archive_chunk(server_id, created_at DESC);

-- Gift codes (manual SQL insert, redeemable via API)
CREATE TABLE IF NOT EXISTS gift_code (
  id              BIGSERIAL PRIMARY KEY,
  code            VARCHAR(64) UNIQUE NOT NULL,
  plan            VARCHAR(32) NOT NULL DEFAULT 'monthly',
  duration_days   INT         NOT NULL DEFAULT 30,
  redeemed_by     VARCHAR(36) REFERENCES users(id) ON DELETE SET NULL,
  redeemed_email  VARCHAR(255),
  redeemed_at     TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_gift_code_code ON gift_code(code);
CREATE INDEX IF NOT EXISTS idx_gift_code_redeemed ON gift_code(redeemed_by);

-- Stripe payment integration
ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_customer_id VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_subscription_id VARCHAR(64);

CREATE TABLE IF NOT EXISTS stripe_event (
  id                BIGSERIAL PRIMARY KEY,
  user_id           VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  event_type        VARCHAR(64) NOT NULL,
  stripe_session_id VARCHAR(128),
  plan              VARCHAR(32),
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_stripe_event_user ON stripe_event(user_id, created_at DESC);

-- Idempotency ledger for Stripe webhook event IDs. Stripe retries webhooks on any
-- non-2xx response (and on network failure) so we MUST treat handlers as
-- at-most-once: the first INSERT claims the event, subsequent retries return 200
-- without re-running the handler.
CREATE TABLE IF NOT EXISTS stripe_event_idempotency (
  stripe_event_id VARCHAR(64) PRIMARY KEY,
  event_type      VARCHAR(64),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Promo codes (coupon system — validate-then-charge flow)
CREATE TABLE IF NOT EXISTS promo_code (
  id                BIGSERIAL PRIMARY KEY,
  code              VARCHAR(64) UNIQUE NOT NULL,
  type              VARCHAR(16) NOT NULL DEFAULT 'percentage',
  discount_percent  INT,
  discount_grosz    INT,
  applicable_plan   VARCHAR(16),
  max_uses          INT         NOT NULL DEFAULT 1,
  used_count        INT         NOT NULL DEFAULT 0,
  active            BOOLEAN     NOT NULL DEFAULT TRUE,
  expires_at        TIMESTAMPTZ,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_promo_code_code ON promo_code(code);

CREATE TABLE IF NOT EXISTS promo_code_usage (
  id             BIGSERIAL PRIMARY KEY,
  promo_code_id  BIGINT      NOT NULL REFERENCES promo_code(id),
  user_id        VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  stripe_session VARCHAR(128),
  used_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_promo_usage_code ON promo_code_usage(promo_code_id);
CREATE INDEX IF NOT EXISTS idx_promo_usage_user ON promo_code_usage(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_promo_usage_per_user ON promo_code_usage(promo_code_id, user_id);

-- Track whether a user has ever subscribed (for first_order promo eligibility)
ALTER TABLE users ADD COLUMN IF NOT EXISTS ever_subscribed BOOLEAN NOT NULL DEFAULT FALSE;

-- Subscription configuration (VAT rate, etc.)
CREATE TABLE IF NOT EXISTS subscription_config (
  key            VARCHAR(64) PRIMARY KEY,
  value          TEXT NOT NULL,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO subscription_config (key, value) VALUES ('vat_rate', '0.23')
ON CONFLICT (key) DO NOTHING;

-- Subscription audit log (all subscription-related actions)
CREATE TABLE IF NOT EXISTS subscription_audit_log (
  id          BIGSERIAL PRIMARY KEY,
  user_id     VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  action      VARCHAR(64) NOT NULL,
  details     TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sub_audit_user ON subscription_audit_log(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sub_audit_action ON subscription_audit_log(action, created_at DESC);

-- Per-user Stripe test/live mode flag. TRUE = test keys, FALSE = live keys.
-- Operator flips this in the DB to promote a user to production billing.
ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_test_mode BOOLEAN NOT NULL DEFAULT TRUE;

-- Separate Stripe customer IDs for test and live environments.
-- stripe_customer_id (existing) is treated as the test-mode customer ID.
-- stripe_live_customer_id stores the live-mode customer ID (created when user
-- is first promoted to live billing). Stripe IDs are environment-scoped — a
-- test customer_id will NOT work with live keys and vice-versa.
ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_live_customer_id VARCHAR(64);

-- Stripe recurring price IDs (auto-created or manually configured).
-- PK is (plan_key, mode) because Stripe Products/Prices live in separate
-- accounts for test vs live — a test price_id will NOT work with live keys
-- and vice-versa. Each mode therefore has its own row per plan.
CREATE TABLE IF NOT EXISTS stripe_price_config (
  plan_key      VARCHAR(32) NOT NULL,
  stripe_price_id VARCHAR(128) NOT NULL,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  mode          VARCHAR(8) NOT NULL DEFAULT 'test',
  PRIMARY KEY (plan_key, mode)
);

-- Migration from legacy single-PK schema: pre-existing rows were always created
-- in test mode (the legacy defaultRequestOptions was hard-coded to test). The
-- statements below are idempotent so they run safely on both fresh and migrated
-- databases. Note: schema.sql is split on semicolons by the loader, so we keep
-- each statement plain (no DO blocks).
ALTER TABLE stripe_price_config ADD COLUMN IF NOT EXISTS mode VARCHAR(8) NOT NULL DEFAULT 'test';
ALTER TABLE stripe_price_config DROP CONSTRAINT IF EXISTS stripe_price_config_pkey;
ALTER TABLE stripe_price_config ADD PRIMARY KEY (plan_key, mode);

-- Separate Stripe subscription IDs for test and live environments. Same rationale
-- as stripe_customer_id / stripe_live_customer_id: subscription IDs are scoped to
-- the Stripe account that created them. Without this split, flipping a user from
-- test to live leaves the test subscription un-cancelable (live API can't see it)
-- and silently overwrites the column with the new live subscription id.
ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_live_subscription_id VARCHAR(64);
