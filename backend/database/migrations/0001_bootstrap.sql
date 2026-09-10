PRAGMA foreign_keys = ON;

CREATE TABLE accounts (
  id TEXT PRIMARY KEY NOT NULL,
  status TEXT NOT NULL DEFAULT 'active'
    CHECK (status IN ('active', 'banned', 'deleted')),
  display_name TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  deleted_at TEXT
);

CREATE INDEX idx_accounts_status ON accounts(status);
CREATE INDEX idx_accounts_created_at ON accounts(created_at);

CREATE TABLE devices (
  id TEXT PRIMARY KEY NOT NULL,
  account_id TEXT NOT NULL,
  platform TEXT NOT NULL
    CHECK (platform IN ('android', 'windows', 'macos')),
  display_name TEXT,
  status TEXT NOT NULL DEFAULT 'active'
    CHECK (status IN ('active', 'revoked', 'banned')),
  public_key_spki TEXT NOT NULL,
  crypto_version INTEGER NOT NULL DEFAULT 2,
  token_hash TEXT NOT NULL,
  token_issued_at TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL,
  FOREIGN KEY (account_id) REFERENCES accounts(id)
);

CREATE INDEX idx_devices_account_id ON devices(account_id);
CREATE INDEX idx_devices_status ON devices(status);
CREATE INDEX idx_devices_last_seen_at ON devices(last_seen_at);

CREATE TABLE subscription_sources (
  id TEXT PRIMARY KEY NOT NULL,
  name TEXT NOT NULL,
  source_type TEXT NOT NULL
    CHECK (source_type IN ('managed', 'external')),
  source_url TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  sort_order INTEGER NOT NULL DEFAULT 0,
  cache_ttl_seconds INTEGER NOT NULL DEFAULT 300,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE INDEX idx_subscription_sources_enabled_sort
ON subscription_sources(enabled, sort_order);

CREATE TABLE usage_sessions (
  device_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  reported_at TEXT NOT NULL,
  PRIMARY KEY (device_id, session_id)
);

CREATE INDEX idx_usage_sessions_reported_at
ON usage_sessions(reported_at);

CREATE TABLE usage_daily (
  account_id TEXT NOT NULL,
  day TEXT NOT NULL,
  upload_bytes INTEGER NOT NULL DEFAULT 0,
  download_bytes INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (account_id, day)
);

CREATE INDEX idx_usage_daily_day ON usage_daily(day);

CREATE TABLE usage_monthly (
  account_id TEXT NOT NULL,
  month TEXT NOT NULL,
  upload_bytes INTEGER NOT NULL DEFAULT 0,
  download_bytes INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (account_id, month)
);

CREATE INDEX idx_usage_monthly_month ON usage_monthly(month);

CREATE TABLE crypto_rate_limits (
  scope TEXT NOT NULL,
  identity_hash TEXT NOT NULL,
  window_start INTEGER NOT NULL,
  request_count INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (scope, identity_hash, window_start)
);

CREATE INDEX idx_crypto_rate_limits_window
ON crypto_rate_limits(window_start);

CREATE TABLE crypto_replay_nonces (
  device_id TEXT NOT NULL,
  challenge_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  PRIMARY KEY (device_id, challenge_hash)
);

CREATE INDEX idx_crypto_replay_expires_at
ON crypto_replay_nonces(expires_at);

CREATE TABLE admin_audit_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  action TEXT NOT NULL,
  target_type TEXT,
  target_id TEXT,
  request_id TEXT,
  ip_hash TEXT,
  detail_json TEXT,
  created_at TEXT NOT NULL
);

CREATE INDEX idx_admin_audit_logs_created_at
ON admin_audit_logs(created_at);
