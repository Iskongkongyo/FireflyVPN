export async function readAnonymousAccount(
  db: D1Database,
  accountId: string,
): Promise<Record<string, unknown> | null> {
  return db.prepare(`
    SELECT id, status, display_name AS displayName,
           created_at AS createdAt, updated_at AS updatedAt
    FROM accounts WHERE id = ?1 LIMIT 1
  `).bind(accountId).first<Record<string, unknown>>();
}

export async function deleteAnonymousAccount(
  db: D1Database,
  accountId: string,
  nowIso: string,
): Promise<number> {
  const results = await db.batch([
    db.prepare(`
      UPDATE accounts
      SET status = 'deleted', display_name = NULL, deleted_at = ?2, updated_at = ?2
      WHERE id = ?1 AND status != 'deleted'
    `).bind(accountId, nowIso),
    db.prepare(`
      UPDATE devices SET status = 'revoked', updated_at = ?2
      WHERE account_id = ?1 AND status != 'revoked'
    `).bind(accountId, nowIso),
    db.prepare(`
      DELETE FROM crypto_replay_nonces
      WHERE device_id IN (SELECT id FROM devices WHERE account_id = ?1)
    `).bind(accountId),
  ]);
  if ((results[0]?.meta.changes ?? 0) !== 1) return -1;
  return Number(results[1]?.meta.changes ?? 0);
}
