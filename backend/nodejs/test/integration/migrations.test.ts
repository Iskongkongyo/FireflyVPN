import { afterEach, beforeEach, describe, expect, it } from "vitest";

import bootstrapSql from "../../database/migrations/0001_bootstrap.sql?raw";
import anonymousAccountsSql from "../../database/migrations/0002_anonymous_accounts.sql?raw";
import subscriptionNotesSql from "../../database/migrations/0003_subscription_notes.sql?raw";
import auditIpAddressSql from "../../database/migrations/0004_audit_ip_address.sql?raw";
import adminJwtSql from "../../database/migrations/0005_admin_jwt.sql?raw";
import writeOptimizationSql from "../../database/migrations/0006_d1_write_optimization.sql?raw";
import externalSourceHealthSql from "../../database/migrations/0007_external_source_health.sql?raw";
import subscriptionMergeModeSql from "../../database/migrations/0008_subscription_merge_mode.sql?raw";
import type { SubscriptionSourceRecord } from "../../src/modules/subscription-catalog/models";
import { recordExternalHealth } from "../../src/modules/subscription-catalog/repository";
import { NodeDatabase } from "../../src/node/database";

async function executeSql(db: Database, sql: string): Promise<void> {
  await db.exec(sql);
}

describe("database migrations", () => {
  let database: NodeDatabase;
  let db: Database;

  beforeEach(() => {
    database = new NodeDatabase(":memory:");
    db = database.asDatabase();
  });

  afterEach(() => database.close());

  it("removes legacy identity columns without losing populated account relations", async () => {
    await executeSql(db, bootstrapSql);
    const accountId = "a".repeat(64);
    const deviceId = "b".repeat(64);
    const now = "2026-09-06T12:00:00.000Z";
    await db.batch([
      db.prepare(`
        INSERT INTO accounts (id, status, created_at, updated_at)
        VALUES (?1, 'active', ?2, ?2)
      `).bind(accountId, now),
      db.prepare(`
        INSERT INTO devices (
          id, account_id, platform, status, public_key_spki, crypto_version,
          token_hash, token_issued_at, created_at, updated_at, last_seen_at
        ) VALUES (?1, ?2, 'android', 'active', 'fixture', 2, 'hash', ?3, ?3, ?3, ?3)
      `).bind(deviceId, accountId, now),
      db.prepare(`
        INSERT INTO usage_daily (account_id, day, upload_bytes, download_bytes)
        VALUES (?1, '2026-09-06', 10, 20)
      `).bind(accountId),
    ]);

    await executeSql(db, anonymousAccountsSql);

    const counts = await db.prepare(`
      SELECT (SELECT COUNT(*) FROM accounts) AS accounts,
             (SELECT COUNT(*) FROM devices) AS devices,
             (SELECT COUNT(*) FROM usage_daily) AS usageRows
    `).first<{ accounts: number; devices: number; usageRows: number }>();
    expect(counts).toEqual({ accounts: 1, devices: 1, usageRows: 1 });
    const violations = await db.prepare("PRAGMA foreign_key_check").all();
    expect(violations.results).toEqual([]);
    const foreignKeys = await db.prepare("PRAGMA foreign_key_list(devices)")
      .all<{ table: string; from: string; to: string }>();
    expect(foreignKeys.results).toEqual([
      expect.objectContaining({ table: "accounts", from: "account_id", to: "id" }),
    ]);
    const columns = await db.prepare("PRAGMA table_info(accounts)").all<{ name: string }>();
    expect(columns.results.map((column) => column.name)).toEqual([
      "id", "status", "display_name", "created_at", "updated_at", "deleted_at",
    ]);
  });

  it("adds audit IP addresses without losing existing audit rows", async () => {
    await executeSql(db, bootstrapSql);
    const now = "2026-09-06T12:00:00.000Z";
    await db.prepare(`
      INSERT INTO admin_audit_logs (
        action, target_type, target_id, request_id, ip_hash, detail_json, created_at
      ) VALUES ('admin.login', 'admin-session', 'admin', 'req-1', 'hash-1', '{}', ?1)
    `).bind(now).run();

    await executeSql(db, auditIpAddressSql);
    const row = await db.prepare(`
      SELECT action, request_id AS requestId, ip_hash AS ipHash, ip_address AS ipAddress
      FROM admin_audit_logs WHERE request_id = 'req-1'
    `).first<{ action: string; requestId: string; ipHash: string; ipAddress: string | null }>();
    expect(row).toEqual({
      action: "admin.login",
      requestId: "req-1",
      ipHash: "hash-1",
      ipAddress: null,
    });
  });

  it("adds persistent JWT settings and session revocation storage", async () => {
    await executeSql(db, bootstrapSql);
    await executeSql(db, adminJwtSql);
    const tables = await db.prepare(`
      SELECT name FROM sqlite_master
      WHERE type = 'table' AND name IN ('admin_security_settings', 'admin_revoked_sessions')
      ORDER BY name
    `).all<{ name: string }>();
    expect(tables.results.map((row) => row.name)).toEqual([
      "admin_revoked_sessions",
      "admin_security_settings",
    ]);
  });

  it("adds optional subscription notes without losing existing sources", async () => {
    await executeSql(db, bootstrapSql);
    const now = "2026-09-06T12:00:00.000Z";
    await db.prepare(`
      INSERT INTO subscription_sources (
        id, name, source_type, enabled, sort_order, cache_ttl_seconds, created_at, updated_at
      ) VALUES ('main', '主线路', 'managed', 1, 0, 300, ?1, ?1)
    `).bind(now).run();

    await executeSql(db, subscriptionNotesSql);
    const source = await db.prepare("SELECT id, name, note FROM subscription_sources WHERE id = 'main'")
      .first<{ id: string; name: string; note: string | null }>();
    expect(source).toEqual({ id: "main", name: "主线路", note: null });
  });

  it("enables health monitoring for existing external sources", async () => {
    await executeSql(db, bootstrapSql);
    const now = "2026-09-06T12:00:00.000Z";
    await db.batch([
      db.prepare(`INSERT INTO subscription_sources (
        id, name, source_type, source_url, enabled, sort_order,
        cache_ttl_seconds, created_at, updated_at
      ) VALUES ('external', '外源', 'external', 'https://example.com/sub', 1, 0, 300, ?1, ?1)`).bind(now),
      db.prepare(`INSERT INTO subscription_sources (
        id, name, source_type, enabled, sort_order,
        cache_ttl_seconds, created_at, updated_at
      ) VALUES ('managed', '托管', 'managed', 1, 0, 300, ?1, ?1)`).bind(now),
    ]);

    await executeSql(db, externalSourceHealthSql);

    const rows = await db.prepare(`
      SELECT id, external_health_enabled AS enabled, external_health_status AS status
      FROM subscription_sources ORDER BY id
    `).all<{ id: string; enabled: number; status: string }>();
    expect(rows.results).toEqual([
      { id: "external", enabled: 1, status: "unknown" },
      { id: "managed", enabled: 0, status: "disabled" },
    ]);
  });

  it("does not let a stale check overwrite health after the source URL changes", async () => {
    await executeSql(db, `${bootstrapSql}\n${externalSourceHealthSql}`);
    const oldTimestamp = "2026-09-06T12:00:00.000Z";
    const nextTimestamp = "2026-09-06T12:00:01.000Z";
    await db.prepare(`INSERT INTO subscription_sources (
      id, name, source_type, source_url, enabled, sort_order, cache_ttl_seconds,
      external_health_enabled, external_health_status, created_at, updated_at
    ) VALUES ('external', '外源', 'external', 'https://example.com/old', 1, 0, 300, 1, 'unknown', ?1, ?1)`)
      .bind(oldTimestamp).run();
    const staleSource = {
      id: "external",
      sourceUrl: "https://example.com/old",
      updatedAt: oldTimestamp,
    } as SubscriptionSourceRecord;
    await db.prepare(`UPDATE subscription_sources
      SET source_url = 'https://example.com/new', updated_at = ?1
      WHERE id = 'external'`).bind(nextTimestamp).run();

    await recordExternalHealth(db, staleSource, {
      healthy: true,
      fallbackActive: false,
      errorCode: null,
      checkedAt: nextTimestamp,
    });

    const health = await db.prepare(`SELECT external_health_status AS status,
      external_last_checked_at AS checkedAt FROM subscription_sources WHERE id = 'external'`)
      .first<{ status: string; checkedAt: string | null }>();
    expect(health).toEqual({ status: "unknown", checkedAt: null });
  });

  it("defaults existing subscription sources to external-first merging", async () => {
    await executeSql(db, bootstrapSql);
    const now = "2026-09-06T12:00:00.000Z";
    await db.prepare(`INSERT INTO subscription_sources (
      id, name, source_type, source_url, enabled, sort_order,
      cache_ttl_seconds, created_at, updated_at
    ) VALUES ('external', '外源', 'external', 'https://example.com/sub', 1, 0, 300, ?1, ?1)`)
      .bind(now).run();

    await executeSql(db, subscriptionMergeModeSql);

    const source = await db.prepare(`SELECT merge_mode AS mergeMode
      FROM subscription_sources WHERE id = 'external'`)
      .first<{ mergeMode: string }>();
    expect(source).toEqual({ mergeMode: "external_first" });
    await expect(db.prepare(`UPDATE subscription_sources SET merge_mode = 'invalid'
      WHERE id = 'external'`).run()).rejects.toThrow();
  });

  it("compacts high-churn tables without losing replay or usage idempotency state", async () => {
    await executeSql(db, bootstrapSql);
    await db.batch([
      db.prepare("INSERT INTO crypto_rate_limits VALUES ('scope', 'identity', 1, 2)"),
      db.prepare("INSERT INTO crypto_replay_nonces VALUES ('device', 'challenge', 1, 2)"),
      db.prepare("INSERT INTO usage_sessions VALUES ('device', 'session', 'account', '2026-09-06T12:00:00.000Z')"),
    ]);

    await executeSql(db, writeOptimizationSql);

    const counts = await db.prepare(`
      SELECT (SELECT COUNT(*) FROM crypto_rate_limits) AS rateLimits,
             (SELECT COUNT(*) FROM crypto_replay_nonces) AS replayNonces,
             (SELECT COUNT(*) FROM usage_sessions) AS usageSessions
    `).first<{ rateLimits: number; replayNonces: number; usageSessions: number }>();
    expect(counts).toEqual({ rateLimits: 0, replayNonces: 1, usageSessions: 1 });

    const compactTables = await db.prepare(`
      SELECT name, sql FROM sqlite_master
      WHERE type = 'table'
        AND name IN ('crypto_rate_limits', 'crypto_replay_nonces', 'usage_sessions')
      ORDER BY name
    `).all<{ name: string; sql: string }>();
    expect(compactTables.results).toHaveLength(3);
    expect(compactTables.results.every((table) => /WITHOUT ROWID/iu.test(table.sql))).toBe(true);

    const removedIndexes = await db.prepare(`
      SELECT name FROM sqlite_master
      WHERE type = 'index'
        AND name IN (
          'idx_devices_last_seen_at',
          'idx_crypto_rate_limits_window',
          'idx_crypto_replay_expires_at',
          'idx_usage_sessions_reported_at'
        )
    `).all<{ name: string }>();
    expect(removedIndexes.results).toEqual([]);
  });
});
