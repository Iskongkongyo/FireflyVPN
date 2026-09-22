-- Compact high-churn tables so primary-key maintenance does not create a
-- separate row write. Their cleanup queries intentionally scan small,
-- short-lived tables to avoid a second indexed write on every mutation.

DROP INDEX IF EXISTS idx_devices_last_seen_at;

DROP INDEX IF EXISTS idx_crypto_rate_limits_window;
CREATE TABLE crypto_rate_limits_compact (
  scope TEXT NOT NULL,
  identity_hash TEXT NOT NULL,
  window_start INTEGER NOT NULL,
  request_count INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (scope, identity_hash, window_start)
) WITHOUT ROWID;
DROP TABLE crypto_rate_limits;
ALTER TABLE crypto_rate_limits_compact RENAME TO crypto_rate_limits;

DROP INDEX IF EXISTS idx_crypto_replay_expires_at;
CREATE TABLE crypto_replay_nonces_compact (
  device_id TEXT NOT NULL,
  challenge_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  PRIMARY KEY (device_id, challenge_hash)
) WITHOUT ROWID;
INSERT INTO crypto_replay_nonces_compact (
  device_id, challenge_hash, created_at, expires_at
)
SELECT device_id, challenge_hash, created_at, expires_at
FROM crypto_replay_nonces;
DROP TABLE crypto_replay_nonces;
ALTER TABLE crypto_replay_nonces_compact RENAME TO crypto_replay_nonces;

DROP INDEX IF EXISTS idx_usage_sessions_reported_at;
CREATE TABLE usage_sessions_compact (
  device_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  reported_at TEXT NOT NULL,
  PRIMARY KEY (device_id, session_id)
) WITHOUT ROWID;
INSERT INTO usage_sessions_compact (
  device_id, session_id, account_id, reported_at
)
SELECT device_id, session_id, account_id, reported_at
FROM usage_sessions;
DROP TABLE usage_sessions;
ALTER TABLE usage_sessions_compact RENAME TO usage_sessions;
