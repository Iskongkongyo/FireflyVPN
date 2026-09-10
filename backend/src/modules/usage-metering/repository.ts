import type { AuthenticatedDevice } from "../secure-delivery/authenticator";
import type { UsageReportInput } from "./models";

export async function insertUsageSession(
  db: D1Database,
  device: AuthenticatedDevice,
  report: UsageReportInput,
  nowIso: string,
): Promise<boolean> {
  const result = await db.prepare(`
    INSERT OR IGNORE INTO usage_sessions (
      device_id, session_id, account_id, reported_at
    ) VALUES (?1, ?2, ?3, ?4)
  `).bind(device.deviceId, report.sessionId, device.accountId, nowIso).run();
  return (result.meta.changes ?? 0) === 1;
}

export async function aggregateUsage(
  db: D1Database,
  device: AuthenticatedDevice,
  report: UsageReportInput,
  day: string,
  month: string,
  nowIso: string,
): Promise<void> {
  try {
    await db.batch([
      db.prepare(`
        INSERT INTO usage_daily (
          account_id, day, upload_bytes, download_bytes
        ) VALUES (?1, ?2, ?3, ?4)
        ON CONFLICT(account_id, day) DO UPDATE SET
          upload_bytes = upload_bytes + excluded.upload_bytes,
          download_bytes = download_bytes + excluded.download_bytes
      `).bind(device.accountId, day, report.uploadBytes, report.downloadBytes),
      db.prepare(`
        INSERT INTO usage_monthly (
          account_id, month, upload_bytes, download_bytes
        ) VALUES (?1, ?2, ?3, ?4)
        ON CONFLICT(account_id, month) DO UPDATE SET
          upload_bytes = upload_bytes + excluded.upload_bytes,
          download_bytes = download_bytes + excluded.download_bytes
      `).bind(device.accountId, month, report.uploadBytes, report.downloadBytes),
      db.prepare(`
        UPDATE devices SET last_seen_at = ?2, updated_at = ?2 WHERE id = ?1
      `).bind(device.deviceId, nowIso),
    ]);
  } catch (error) {
    // Let a client retry if aggregation failed after the idempotency marker insert.
    try {
      await db.prepare(`
        DELETE FROM usage_sessions WHERE device_id = ?1 AND session_id = ?2
      `).bind(device.deviceId, report.sessionId).run();
    } catch {
      // Preserve the original storage error.
    }
    throw error;
  }
}
