-- Remove legacy email/password identity columns from databases created before
-- Firefly Edge switched to anonymous-only identity. Accounts and their child
-- devices are rebuilt together so populated databases never leave a dangling FK.
PRAGMA defer_foreign_keys = ON;

CREATE TABLE accounts_anonymous (
  id TEXT PRIMARY KEY NOT NULL,
  status TEXT NOT NULL DEFAULT 'active'
    CHECK (status IN ('active', 'banned', 'deleted')),
  display_name TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  deleted_at TEXT
);

CREATE TABLE devices_anonymous (
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
  FOREIGN KEY (account_id) REFERENCES accounts_anonymous(id)
);

INSERT INTO accounts_anonymous (
  id, status, display_name, created_at, updated_at, deleted_at
)
SELECT id, status, display_name, created_at, updated_at, deleted_at
FROM accounts;

INSERT INTO devices_anonymous (
  id, account_id, platform, display_name, status, public_key_spki,
  crypto_version, token_hash, token_issued_at, created_at, updated_at, last_seen_at
)
SELECT id, account_id, platform, display_name, status, public_key_spki,
       crypto_version, token_hash, token_issued_at, created_at, updated_at, last_seen_at
FROM devices;

DROP TABLE devices;
DROP TABLE accounts;
ALTER TABLE accounts_anonymous RENAME TO accounts;
ALTER TABLE devices_anonymous RENAME TO devices;

CREATE INDEX idx_accounts_status ON accounts(status);
CREATE INDEX idx_accounts_created_at ON accounts(created_at);
CREATE INDEX idx_devices_account_id ON devices(account_id);
CREATE INDEX idx_devices_status ON devices(status);
CREATE INDEX idx_devices_last_seen_at ON devices(last_seen_at);
