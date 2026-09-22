ALTER TABLE subscription_sources
ADD COLUMN external_health_enabled INTEGER NOT NULL DEFAULT 0
CHECK (external_health_enabled IN (0, 1));

ALTER TABLE subscription_sources
ADD COLUMN external_health_status TEXT NOT NULL DEFAULT 'disabled'
CHECK (external_health_status IN ('disabled', 'unknown', 'healthy', 'unhealthy'));

ALTER TABLE subscription_sources ADD COLUMN external_last_checked_at TEXT;
ALTER TABLE subscription_sources ADD COLUMN external_last_success_at TEXT;
ALTER TABLE subscription_sources ADD COLUMN external_last_error TEXT;

ALTER TABLE subscription_sources
ADD COLUMN external_fallback_active INTEGER NOT NULL DEFAULT 0
CHECK (external_fallback_active IN (0, 1));

UPDATE subscription_sources
SET external_health_enabled = 1,
    external_health_status = 'unknown'
WHERE source_type = 'external';
