import type {
  SubscriptionSourceInput,
  SubscriptionSourceRecord,
} from "./models";

const SOURCE_COLUMNS = `
  id,
  name,
  note,
  source_type AS sourceType,
  source_url AS sourceUrl,
  enabled,
  sort_order AS sortOrder,
  cache_ttl_seconds AS cacheTtlSeconds,
  created_at AS createdAt,
  updated_at AS updatedAt
`;

export async function listEnabledSources(
  db: D1Database,
): Promise<SubscriptionSourceRecord[]> {
  const result = await db.prepare(`
    SELECT ${SOURCE_COLUMNS}
    FROM subscription_sources
    WHERE enabled = 1
    ORDER BY sort_order ASC, created_at ASC, id ASC
  `).all<SubscriptionSourceRecord>();
  return result.results;
}

export async function listAllSources(
  db: D1Database,
): Promise<SubscriptionSourceRecord[]> {
  const result = await db.prepare(`
    SELECT ${SOURCE_COLUMNS}
    FROM subscription_sources
    ORDER BY sort_order ASC, created_at ASC, id ASC
  `).all<SubscriptionSourceRecord>();
  return result.results;
}

export async function findSource(
  db: D1Database,
  sourceId: string,
  enabledOnly = false,
): Promise<SubscriptionSourceRecord | null> {
  return db.prepare(`
    SELECT ${SOURCE_COLUMNS}
    FROM subscription_sources
    WHERE id = ?1 AND (?2 = 0 OR enabled = 1)
    LIMIT 1
  `).bind(sourceId, enabledOnly ? 1 : 0).first<SubscriptionSourceRecord>();
}

export async function insertSource(
  db: D1Database,
  input: SubscriptionSourceInput,
  nowIso: string,
): Promise<void> {
  await db.prepare(`
    INSERT INTO subscription_sources (
      id, name, note, source_type, source_url, enabled, sort_order,
      cache_ttl_seconds, created_at, updated_at
    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?9)
  `).bind(
    input.id,
    input.name,
    input.note,
    input.sourceType,
    input.sourceUrl,
    input.enabled ? 1 : 0,
    input.sortOrder,
    input.cacheTtlSeconds,
    nowIso,
  ).run();
}

export async function updateSource(
  db: D1Database,
  input: SubscriptionSourceInput,
  nowIso: string,
): Promise<void> {
  await db.prepare(`
    UPDATE subscription_sources SET
      name = ?2,
      note = ?3,
      source_type = ?4,
      source_url = ?5,
      enabled = ?6,
      sort_order = ?7,
      cache_ttl_seconds = ?8,
      updated_at = ?9
    WHERE id = ?1
  `).bind(
    input.id,
    input.name,
    input.note,
    input.sourceType,
    input.sourceUrl,
    input.enabled ? 1 : 0,
    input.sortOrder,
    input.cacheTtlSeconds,
    nowIso,
  ).run();
}

export async function deleteSource(db: D1Database, sourceId: string): Promise<void> {
  await db.prepare("DELETE FROM subscription_sources WHERE id = ?1")
    .bind(sourceId)
    .run();
}
