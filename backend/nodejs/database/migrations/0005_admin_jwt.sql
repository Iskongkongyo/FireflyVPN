CREATE TABLE admin_security_settings (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  jwt_secret TEXT NOT NULL,
  jwt_secret_source TEXT NOT NULL DEFAULT 'generated'
    CHECK (jwt_secret_source IN ('generated', 'custom')),
  updated_at TEXT NOT NULL
);

CREATE TABLE admin_revoked_sessions (
  jti TEXT PRIMARY KEY,
  expires_at TEXT NOT NULL,
  revoked_at TEXT NOT NULL
);

CREATE INDEX idx_admin_revoked_sessions_expires_at
  ON admin_revoked_sessions(expires_at);
