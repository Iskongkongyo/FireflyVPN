import type {
  ExternalHealthOutcome,
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
  merge_mode AS mergeMode,
  external_health_enabled AS externalHealthEnabled,
  external_health_status AS externalHealthStatus,
  external_last_checked_at AS externalLastCheckedAt,
  external_last_success_at AS externalLastSuccessAt,
  external_last_error AS externalLastError,
  external_fallback_active AS externalFallbackActive,
  created_at AS createdAt,
  updated_at AS updatedAt
`;

export async function listEnabledSources(
  db: Database,
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
  db: Database,
): Promise<SubscriptionSourceRecord[]> {
  const result = await db.prepare(`
    SELECT ${SOURCE_COLUMNS}
    FROM subscription_sources
    ORDER BY sort_order ASC, created_at ASC, id ASC
  `).all<SubscriptionSourceRecord>();
  return result.results;
}

export async function listMonitoredExternalSources(
  db: Database,
): Promise<SubscriptionSourceRecord[]> {
  const result = await db.prepare(`
    SELECT ${SOURCE_COLUMNS}
    FROM subscription_sources
    WHERE enabled = 1
      AND source_type = 'external'
      AND external_health_enabled = 1
    ORDER BY id ASC
  `).all<SubscriptionSourceRecord>();
  return result.results;
}

export async function findSource(
  db: Database,
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
  db: Database,
  input: SubscriptionSourceInput,
  nowIso: string,
): Promise<void> {
  await db.prepare(`
    INSERT INTO subscription_sources (
      id, name, note, source_type, source_url, enabled, sort_order,
      cache_ttl_seconds, merge_mode, external_health_enabled, external_health_status,
      created_at, updated_at
    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?12)
  `).bind(
    input.id,
    input.name,
    input.note,
    input.sourceType,
    input.sourceUrl,
    input.enabled ? 1 : 0,
    input.sortOrder,
    input.cacheTtlSeconds,
    input.mergeMode,
    input.externalHealthEnabled ? 1 : 0,
    input.externalHealthEnabled ? "unknown" : "disabled",
    nowIso,
  ).run();
}

export async function updateSource(
  db: Database,
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
      merge_mode = ?9,
      external_health_enabled = ?10,
      external_health_status = CASE
        WHEN ?4 <> 'external' OR ?10 = 0 THEN 'disabled'
        WHEN NOT (source_url IS ?5) OR external_health_enabled <> ?10 THEN 'unknown'
        ELSE external_health_status
      END,
      external_last_checked_at = CASE
        WHEN NOT (source_url IS ?5) OR external_health_enabled <> ?10 THEN NULL
        ELSE external_last_checked_at
      END,
      external_last_success_at = CASE
        WHEN NOT (source_url IS ?5) THEN NULL
        ELSE external_last_success_at
      END,
      external_last_error = CASE
        WHEN ?4 <> 'external' OR ?10 = 0 OR NOT (source_url IS ?5) OR external_health_enabled <> ?10 THEN NULL
        ELSE external_last_error
      END,
      external_fallback_active = CASE
        WHEN ?4 <> 'external' OR ?10 = 0 OR NOT (source_url IS ?5) OR external_health_enabled <> ?10 THEN 0
        ELSE external_fallback_active
      END,
      updated_at = ?11
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
    input.mergeMode,
    input.externalHealthEnabled ? 1 : 0,
    nowIso,
  ).run();
}

export async function recordExternalHealth(
  db: Database,
  source: SubscriptionSourceRecord,
  outcome: ExternalHealthOutcome,
): Promise<void> {
  await db.prepare(`
    UPDATE subscription_sources SET
      external_health_status = ?2,
      external_last_checked_at = ?3,
      external_last_success_at = CASE WHEN ?4 = 1 THEN ?3 ELSE external_last_success_at END,
      external_last_error = ?5,
      external_fallback_active = ?6
    WHERE id = ?1
      AND source_type = 'external'
      AND external_health_enabled = 1
      AND updated_at = ?7
      AND source_url IS ?8
  `).bind(
    source.id,
    outcome.healthy ? "healthy" : "unhealthy",
    outcome.checkedAt,
    outcome.healthy ? 1 : 0,
    outcome.errorCode,
    outcome.fallbackActive ? 1 : 0,
    source.updatedAt,
    source.sourceUrl,
  ).run();
}

export async function deleteSource(db: Database, sourceId: string): Promise<void> {
  await db.prepare("DELETE FROM subscription_sources WHERE id = ?1")
    .bind(sourceId)
    .run();
}
