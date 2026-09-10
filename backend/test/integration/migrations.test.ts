import { convertV4MiniflareOptions, Miniflare } from "miniflare";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import bootstrapSql from "../../database/migrations/0001_bootstrap.sql?raw";
import anonymousAccountsSql from "../../database/migrations/0002_anonymous_accounts.sql?raw";
import subscriptionNotesSql from "../../database/migrations/0003_subscription_notes.sql?raw";
import auditIpAddressSql from "../../database/migrations/0004_audit_ip_address.sql?raw";
import adminJwtSql from "../../database/migrations/0005_admin_jwt.sql?raw";

async function executeSql(db: D1Database, sql: string): Promise<void> {
  const statements = sql
    .split(";")
    .map((statement) => statement.trim())
    .filter(Boolean);
  for (const statement of statements) await db.prepare(statement).run();
}

describe("D1 migrations", () => {
  let miniflare: Miniflare;
  let db: D1Database;

  beforeEach(async () => {
    miniflare = new Miniflare(convertV4MiniflareOptions({
      compatibilityDate: "2026-09-06",
      compatibilityFlags: ["nodejs_compat"],
      modules: true,
      script: "export default { fetch() { return new Response('ok') } }",
      d1Databases: ["DB"],
    }));
    db = await miniflare.getD1Database("DB") as unknown as D1Database;
  });

  afterEach(async () => {
    await miniflare.dispose();
  });

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
});
