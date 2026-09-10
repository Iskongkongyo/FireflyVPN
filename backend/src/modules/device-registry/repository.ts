import type { DeviceRecord, EnrollDeviceInput } from "./models";

export async function findDevice(
  db: D1Database,
  deviceId: string,
): Promise<DeviceRecord | null> {
  return db.prepare(`
    SELECT
      d.id,
      d.account_id AS accountId,
      d.platform,
      d.display_name AS displayName,
      d.status,
      d.public_key_spki AS publicKeySpki,
      d.crypto_version AS cryptoVersion,
      d.token_hash AS tokenHash,
      d.token_issued_at AS tokenIssuedAt,
      d.created_at AS createdAt,
      d.updated_at AS updatedAt,
      d.last_seen_at AS lastSeenAt,
      a.status AS accountStatus
    FROM devices d
    JOIN accounts a ON a.id = d.account_id
    WHERE d.id = ?1
    LIMIT 1
  `).bind(deviceId).first<DeviceRecord>();
}

export async function createAnonymousDevice(
  db: D1Database,
  accountId: string,
  input: EnrollDeviceInput,
  tokenHash: string,
  nowIso: string,
): Promise<void> {
  await db.batch([
    db.prepare(`
      INSERT INTO accounts (id, status, created_at, updated_at)
      VALUES (?1, 'active', ?2, ?2)
    `).bind(accountId, nowIso),
    db.prepare(`
      INSERT INTO devices (
        id, account_id, platform, display_name, status,
        public_key_spki, crypto_version, token_hash, token_issued_at,
        created_at, updated_at, last_seen_at
      ) VALUES (?1, ?2, ?3, ?4, 'active', ?5, 2, ?6, ?7, ?7, ?7, ?7)
    `).bind(
      input.deviceId,
      accountId,
      input.platform,
      input.deviceName,
      input.publicKeySpki,
      tokenHash,
      nowIso,
    ),
  ]);
}

export async function touchDevice(
  db: D1Database,
  deviceId: string,
  nowIso: string,
): Promise<void> {
  await db.prepare(`
    UPDATE devices
    SET last_seen_at = ?2, updated_at = ?2
    WHERE id = ?1
  `).bind(deviceId, nowIso).run();
}

export async function listAccountDevices(
  db: D1Database,
  accountId: string,
): Promise<DeviceRecord[]> {
  const result = await db.prepare(`
    SELECT
      d.id,
      d.account_id AS accountId,
      d.platform,
      d.display_name AS displayName,
      d.status,
      d.public_key_spki AS publicKeySpki,
      d.crypto_version AS cryptoVersion,
      d.token_hash AS tokenHash,
      d.token_issued_at AS tokenIssuedAt,
      d.created_at AS createdAt,
      d.updated_at AS updatedAt,
      d.last_seen_at AS lastSeenAt,
      a.status AS accountStatus
    FROM devices d
    JOIN accounts a ON a.id = d.account_id
    WHERE d.account_id = ?1
    ORDER BY d.created_at ASC
  `).bind(accountId).all<DeviceRecord>();
  return result.results;
}

export async function revokeAccountDevice(
  db: D1Database,
  accountId: string,
  targetDeviceId: string,
  currentDeviceId: string,
  nowIso: string,
): Promise<boolean> {
  const result = await db.prepare(`
    UPDATE devices
    SET status = 'revoked', updated_at = ?4
    WHERE id = ?2 AND account_id = ?1 AND id != ?3 AND status != 'revoked'
  `).bind(accountId, targetDeviceId, currentDeviceId, nowIso).run();
  return (result.meta.changes ?? 0) === 1;
}

export async function rotateDeviceCredentials(
  db: D1Database,
  deviceId: string,
  currentTokenHash: string,
  publicKeySpki: string,
  nextTokenHash: string,
  nowIso: string,
): Promise<boolean> {
  const results = await db.batch([
    db.prepare(`
      UPDATE devices
      SET public_key_spki = ?3,
          token_hash = ?4,
          token_issued_at = ?5,
          updated_at = ?5,
          last_seen_at = ?5
      WHERE id = ?1 AND token_hash = ?2 AND status = 'active'
    `).bind(deviceId, currentTokenHash, publicKeySpki, nextTokenHash, nowIso),
    db.prepare("DELETE FROM crypto_replay_nonces WHERE device_id = ?1").bind(deviceId),
  ]);
  return (results[0]?.meta.changes ?? 0) === 1;
}
