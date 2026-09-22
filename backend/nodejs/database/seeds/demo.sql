-- Idempotent, removable demo data for the operations console.
-- All IDs are deterministic and reserved exclusively for this seed.

INSERT INTO accounts (id, status, display_name, created_at, updated_at)
VALUES
  (printf('%064x', 9001), 'active', NULL, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-21 days'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  (printf('%064x', 9002), 'active', NULL, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-14 days'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  (printf('%064x', 9003), 'active', NULL, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-8 days'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  (printf('%064x', 9004), 'active', NULL, strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-45 days'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
ON CONFLICT(id) DO UPDATE SET updated_at = excluded.updated_at;

INSERT INTO devices (
  id, account_id, platform, display_name, status, public_key_spki,
  crypto_version, token_hash, token_issued_at, created_at, updated_at, last_seen_at
)
VALUES
  (printf('%064x', 10001), printf('%064x', 9001), 'android', '演示 Pixel 设备', 'active',
   'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaxfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpZP40Li_hp_m47n60p8D54WK84zV2sxXs7LtkBoN79R9Q',
   2, 'Q-T8HFTJ4CnUE33cWg1TlHafbj5wvRHdrmt-pa1i8cM', strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-21 days'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-21 days'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  (printf('%064x', 10002), printf('%064x', 9002), 'windows', '演示 Windows 桌面', 'active',
   'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfPJ7GI0DT36KUjgDBLUaw8CJaeJ38hs1pgtI_EdmmXgHd1UQ247QQCk9msafdDDbun2t5jzpgimeBLedInhz0Q',
   2, 'fPJ7GI0DT36KUjgDBLUaw8CJaeJ38hs1pgtI_EdmmXg', strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-14 days'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-14 days'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-1 day')),
  (printf('%064x', 10003), printf('%064x', 9003), 'macos', '演示 MacBook', 'banned',
   'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaxfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpZP40Li_hp_m47n60p8D54WK84zV2sxXs7LtkBoN79R9Q',
   2, 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-8 days'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-8 days'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-5 days')),
  (printf('%064x', 10004), printf('%064x', 9004), 'android', '演示已撤销设备', 'revoked',
   'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfPJ7GI0DT36KUjgDBLUaw8CJaeJ38hs1pgtI_EdmmXgHd1UQ247QQCk9msafdDDbun2t5jzpgimeBLedInhz0Q',
   2, 'BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB', strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-45 days'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-45 days'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-40 days'))
ON CONFLICT(id) DO UPDATE SET
  display_name = excluded.display_name,
  status = excluded.status,
  updated_at = excluded.updated_at,
  last_seen_at = excluded.last_seen_at;

DELETE FROM usage_daily
WHERE account_id IN (printf('%064x', 9001), printf('%064x', 9002), printf('%064x', 9003), printf('%064x', 9004));

WITH RECURSIVE days(n) AS (
  SELECT 0 UNION ALL SELECT n + 1 FROM days WHERE n < 13
)
INSERT INTO usage_daily (account_id, day, upload_bytes, download_bytes)
SELECT printf('%064x', 9001), date('now', '+8 hours', printf('-%d days', n)),
       (14 - n) * 7340032, (14 - n) * 19922944
FROM days;

INSERT INTO usage_daily (account_id, day, upload_bytes, download_bytes)
VALUES (
  printf('%064x', 9002), date('now', '+8 hours'), 157286400, 524288000
);

DELETE FROM usage_monthly
WHERE account_id IN (printf('%064x', 9001), printf('%064x', 9002), printf('%064x', 9003), printf('%064x', 9004));

WITH RECURSIVE months(n) AS (
  SELECT 0 UNION ALL SELECT n + 1 FROM months WHERE n < 5
)
INSERT INTO usage_monthly (account_id, month, upload_bytes, download_bytes)
SELECT printf('%064x', 9001), strftime('%Y-%m', 'now', '+8 hours', printf('-%d months', n)),
       (6 - n) * 1073741824, (6 - n) * 3221225472
FROM months;

INSERT INTO usage_monthly (account_id, month, upload_bytes, download_bytes)
VALUES (
  printf('%064x', 9002), strftime('%Y-%m', 'now', '+8 hours'), 2147483648, 5368709120
);

INSERT INTO subscription_sources (
  id, name, source_type, source_url, enabled, sort_order,
  cache_ttl_seconds, external_health_enabled, external_health_status, created_at, updated_at
) VALUES (
  'demo-main', '演示线路（不可用）', 'external', 'https://example.com/subscription', 1, 999, 300, 1, 'unknown',
  strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
)
ON CONFLICT(id) DO UPDATE SET
  name = excluded.name,
  source_type = excluded.source_type,
  source_url = excluded.source_url,
  external_health_enabled = 1,
  external_health_status = 'unknown',
  external_last_checked_at = NULL,
  external_last_error = NULL,
  external_fallback_active = 0,
  enabled = 1,
  sort_order = excluded.sort_order,
  updated_at = excluded.updated_at;

DELETE FROM admin_audit_logs WHERE action = 'demo.seed' AND target_id = 'demo-main';
INSERT INTO admin_audit_logs (
  action, target_type, target_id, request_id, ip_hash, detail_json, created_at
) VALUES (
  'demo.seed', 'demo-data', 'demo-main', 'demo-seed', NULL,
  '{"accounts":4,"devices":4,"days":14,"safeToDelete":true}',
  strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
);
