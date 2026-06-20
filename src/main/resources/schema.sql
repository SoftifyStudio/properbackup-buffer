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
ALTER TABLE users ADD COLUMN IF NOT EXISTS storage_limit_bytes BIGINT NOT NULL DEFAULT 2199023255552;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_trial_notification VARCHAR(16);

-- Set trial_expires_at for existing users if NULL (30 days from creation)
UPDATE users SET trial_expires_at = created_at + INTERVAL '30 days' WHERE trial_expires_at IS NULL AND subscription_plan IS NULL;

CREATE TABLE IF NOT EXISTS servers (
  id           VARCHAR(36)  PRIMARY KEY,
  name         VARCHAR(255) NOT NULL,
  user_id      VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  activated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  last_seen_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS activation_tokens (
  token      VARCHAR(16)  PRIMARY KEY,
  user_id    VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at TIMESTAMPTZ  NOT NULL,
  used       BOOLEAN      NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

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

-- Per-user daily storage cost tracking (OVH Cloud Archive economics)
CREATE TABLE IF NOT EXISTS user_storage_daily (
  id                BIGSERIAL    PRIMARY KEY,
  user_id           VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  day               DATE         NOT NULL,
  stored_bytes      BIGINT       NOT NULL DEFAULT 0,
  uploaded_bytes    BIGINT       NOT NULL DEFAULT 0,
  storage_cost_pln  NUMERIC(12,6) NOT NULL DEFAULT 0,
  upload_cost_pln   NUMERIC(12,6) NOT NULL DEFAULT 0,
  total_cost_pln    NUMERIC(12,6) NOT NULL DEFAULT 0,
  updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  UNIQUE (user_id, day)
);

CREATE INDEX IF NOT EXISTS idx_user_storage_daily_user_day ON user_storage_daily(user_id, day DESC);

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

-- Przyszłość MVP+: archive_manifest_entry (nagłówki tar przy seal) — dodać osobną migracją

-- ============================================================================
-- Recovery Session tables (user-facing-recovery-spec.md Section B)
-- ============================================================================

CREATE TABLE IF NOT EXISTS recovery_session (
  id                 VARCHAR(36)  PRIMARY KEY,
  server_id          VARCHAR(36)  NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
  user_id            VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  target_snapshot_id BIGINT       NOT NULL REFERENCES archive_snapshot(id),
  state              VARCHAR(32)  NOT NULL DEFAULT 'REQUESTED',
  created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  confirmed_at       TIMESTAMPTZ,
  thaw_started_at    TIMESTAMPTZ,
  thaw_completed_at  TIMESTAMPTZ,
  agent_started_at   TIMESTAMPTZ,
  agent_completed_at TIMESTAMPTZ,
  verifying_started_at TIMESTAMPTZ,
  completed_at       TIMESTAMPTZ,
  cancelled_at       TIMESTAMPTZ,
  failed_at          TIMESTAMPTZ,
  pre_recovery_snapshot_id BIGINT REFERENCES archive_snapshot(id),
  dry_run_files_to_restore INT,
  dry_run_files_to_delete  INT,
  dry_run_files_unchanged  INT,
  dry_run_total_bytes      BIGINT,
  files_restored     INT          NOT NULL DEFAULT 0,
  files_deleted      INT          NOT NULL DEFAULT 0,
  bytes_transferred  BIGINT       NOT NULL DEFAULT 0,
  failure_reason     TEXT,
  cancellation_reason TEXT,
  user_confirmed_via_ip    VARCHAR(45),
  user_confirmed_via_user_agent TEXT
);

CREATE INDEX IF NOT EXISTS idx_recovery_session_user ON recovery_session(user_id);
CREATE INDEX IF NOT EXISTS idx_recovery_session_state ON recovery_session(state);
CREATE INDEX IF NOT EXISTS idx_recovery_session_server_active
  ON recovery_session(server_id)
  WHERE state NOT IN ('DONE', 'FAILED', 'CANCELLED');

-- Partial unique: max 1 active recovery per server
CREATE UNIQUE INDEX IF NOT EXISTS uq_recovery_session_server_active
  ON recovery_session(server_id)
  WHERE state NOT IN ('DONE', 'FAILED', 'CANCELLED');

CREATE TABLE IF NOT EXISTS recovery_operation (
  id                   VARCHAR(36) PRIMARY KEY,
  recovery_session_id  VARCHAR(36) NOT NULL REFERENCES recovery_session(id) ON DELETE CASCADE,
  operation_type       VARCHAR(16) NOT NULL,
  path_hash            CHAR(64)    NOT NULL,
  path                 TEXT        NOT NULL,
  sha256_target        CHAR(64),
  sha256_local         CHAR(64),
  size_bytes           BIGINT,
  state                VARCHAR(16) NOT NULL DEFAULT 'pending',
  started_at           TIMESTAMPTZ,
  completed_at         TIMESTAMPTZ,
  error                TEXT
);

CREATE INDEX IF NOT EXISTS idx_recovery_op_session ON recovery_operation(recovery_session_id);
CREATE INDEX IF NOT EXISTS idx_recovery_op_state ON recovery_operation(recovery_session_id, state);

CREATE TABLE IF NOT EXISTS recovery_dry_run (
  id                          VARCHAR(36) PRIMARY KEY,
  recovery_session_id         VARCHAR(36) NOT NULL REFERENCES recovery_session(id) ON DELETE CASCADE,
  computed_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  files_to_restore_count      INT         NOT NULL,
  files_to_restore_size       BIGINT      NOT NULL,
  files_to_delete_count       INT         NOT NULL,
  files_to_delete_size        BIGINT      NOT NULL,
  files_unchanged_count       INT         NOT NULL,
  critical_files_protected_count INT      NOT NULL,
  sample_files_to_restore     TEXT,
  sample_files_to_delete      TEXT,
  estimated_duration_seconds  INT
);

-- Extend archive_snapshot for recovery
ALTER TABLE archive_snapshot ADD COLUMN IF NOT EXISTS snapshot_type VARCHAR(32) NOT NULL DEFAULT 'AUTO';
ALTER TABLE archive_snapshot ADD COLUMN IF NOT EXISTS pre_recovery_session_id VARCHAR(36) REFERENCES recovery_session(id);

-- Audit log table for recovery actions (append-only)
CREATE TABLE IF NOT EXISTS recovery_audit_log (
  id                   BIGSERIAL PRIMARY KEY,
  recovery_session_id  VARCHAR(36) NOT NULL REFERENCES recovery_session(id) ON DELETE CASCADE,
  action               VARCHAR(64) NOT NULL,
  detail               TEXT,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_recovery_audit_session ON recovery_audit_log(recovery_session_id, created_at);

-- ── Notification settings ──
CREATE TABLE IF NOT EXISTS notification_settings (
  user_id                  VARCHAR(36) PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  enabled                  BOOLEAN NOT NULL DEFAULT FALSE,
  email                    VARCHAR(255) NOT NULL DEFAULT '',
  on_agent_offline         BOOLEAN NOT NULL DEFAULT TRUE,
  on_storage_warning       BOOLEAN NOT NULL DEFAULT TRUE,
  on_subscription_expiry   BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Notification queue (pending alerts to be delivered) ──
CREATE TABLE IF NOT EXISTS notification_queue (
  id            BIGSERIAL PRIMARY KEY,
  user_id       VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  alert_type    VARCHAR(32) NOT NULL,
  subject_key   VARCHAR(128) NOT NULL,
  payload       JSONB,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  processed_at  TIMESTAMPTZ,
  status        VARCHAR(16) NOT NULL DEFAULT 'PENDING'
);

CREATE INDEX IF NOT EXISTS idx_notif_queue_pending ON notification_queue(status, created_at) WHERE status = 'PENDING';

-- ── Notification log (deduplication + audit) ──
CREATE TABLE IF NOT EXISTS notification_log (
  id            BIGSERIAL PRIMARY KEY,
  user_id       VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  alert_type    VARCHAR(32) NOT NULL,
  subject_key   VARCHAR(128) NOT NULL,
  sent_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notif_log_dedup ON notification_log(user_id, alert_type, subject_key, sent_at);
