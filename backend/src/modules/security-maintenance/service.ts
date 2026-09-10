export interface SecurityCleanupResult {
  replayNoncesDeleted: number;
  rateLimitRowsDeleted: number;
}

export async function cleanupExpiredSecurityState(
  db: D1Database,
  now = new Date(),
): Promise<SecurityCleanupResult> {
  const nowEpochSeconds = Math.floor(now.getTime() / 1000);
  const currentMinute = Math.floor(now.getTime() / 60_000);
  const results = await db.batch([
    db.prepare("DELETE FROM crypto_replay_nonces WHERE expires_at < ?1")
      .bind(nowEpochSeconds),
    db.prepare("DELETE FROM crypto_rate_limits WHERE window_start < ?1")
      .bind(currentMinute - 2),
  ]);
  return {
    replayNoncesDeleted: Number(results[0]?.meta.changes ?? 0),
    rateLimitRowsDeleted: Number(results[1]?.meta.changes ?? 0),
  };
}
