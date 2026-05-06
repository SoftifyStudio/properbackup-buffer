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

-- Przyszłość MVP+: archive_manifest_entry (nagłówki tar przy seal) — dodać osobną migracją
