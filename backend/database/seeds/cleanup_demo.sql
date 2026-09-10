DELETE FROM crypto_replay_nonces
WHERE device_id IN (printf('%064x', 10001), printf('%064x', 10002), printf('%064x', 10003), printf('%064x', 10004));

DELETE FROM usage_sessions
WHERE device_id IN (printf('%064x', 10001), printf('%064x', 10002), printf('%064x', 10003), printf('%064x', 10004));

DELETE FROM usage_daily
WHERE account_id IN (printf('%064x', 9001), printf('%064x', 9002), printf('%064x', 9003), printf('%064x', 9004));

DELETE FROM usage_monthly
WHERE account_id IN (printf('%064x', 9001), printf('%064x', 9002), printf('%064x', 9003), printf('%064x', 9004));

DELETE FROM devices
WHERE id IN (printf('%064x', 10001), printf('%064x', 10002), printf('%064x', 10003), printf('%064x', 10004));

DELETE FROM accounts
WHERE id IN (printf('%064x', 9001), printf('%064x', 9002), printf('%064x', 9003), printf('%064x', 9004));

DELETE FROM subscription_sources WHERE id = 'demo-main';
DELETE FROM admin_audit_logs
WHERE (action = 'demo.seed' AND target_id = 'demo-main')
   OR target_id IN (printf('%064x', 10001), printf('%064x', 10002), printf('%064x', 10003), printf('%064x', 10004));
