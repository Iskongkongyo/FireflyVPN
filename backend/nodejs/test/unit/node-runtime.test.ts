import BetterSqlite3 from "better-sqlite3";
import {
  existsSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, relative, resolve } from "node:path";
import { afterEach, describe, expect, it } from "vitest";

import { importCloudflareBackup } from "../../src/node/cloudflare-import";
import { applyMigrations, NodeDatabase } from "../../src/node/database";
import { NodeKvStore } from "../../src/node/kv";

const databases: NodeDatabase[] = [];
const temporaryDirectories: string[] = [];

afterEach(() => {
  for (const database of databases.splice(0)) database.close();
  for (const directory of temporaryDirectories.splice(0)) {
    const pathFromTemp = relative(resolve(tmpdir()), resolve(directory));
    if (!pathFromTemp.startsWith("..") && pathFromTemp !== "") {
      rmSync(directory, { recursive: true, force: true });
    }
  }
});

function memoryDatabase(): NodeDatabase {
  const database = new NodeDatabase(":memory:");
  databases.push(database);
  return database;
}

describe("Node.js runtime adapters", () => {
  it("applies migrations once and preserves D1 indexed parameter semantics", async () => {
    const database = memoryDatabase();
    const migrationsDirectory = resolve(process.cwd(), "database/migrations");

    expect(applyMigrations(database, migrationsDirectory)).toHaveLength(8);
    expect(applyMigrations(database, migrationsDirectory)).toEqual([]);

    const row = await database.asDatabase().prepare(`
      SELECT ?2 AS second, ?1 AS first, ?2 AS repeated
    `).bind("one", "two").first<{ first: string; second: string; repeated: string }>();
    expect(row).toEqual({ first: "one", second: "two", repeated: "two" });

    const now = new Date().toISOString();
    const results = await database.asDatabase().batch([
      database.asDatabase().prepare(`
        INSERT INTO accounts (id, status, created_at, updated_at)
        VALUES (?1, 'active', ?2, ?2)
      `).bind("account-one", now),
      database.asDatabase().prepare(`
        UPDATE accounts SET display_name = ?2 WHERE id = ?1
      `).bind("account-one", "Node account"),
    ]);
    expect(results.map((result) => result.meta.changes)).toEqual([1, 1]);
  });

  it("persists KV text, JSON, and metadata in SQLite", async () => {
    const database = memoryDatabase();
    const kv = new NodeKvStore(database.sqlite).asKeyValueStore();

    await kv.put("runtime:config", JSON.stringify({ enabled: true }), {
      metadata: { revision: 3 },
    });
    expect(await kv.get("runtime:config")).toBe('{"enabled":true}');
    expect(await kv.get("runtime:config", "json")).toEqual({ enabled: true });
    expect(await kv.getWithMetadata("runtime:config")).toMatchObject({
      value: '{"enabled":true}',
      metadata: { revision: 3 },
    });

    await kv.delete("runtime:config");
    expect(await kv.get("runtime:config")).toBeNull();
  });

  it("converts D1 SQL and Wrangler KV JSON into a fresh database", async () => {
    const directory = mkdtempSync(join(tmpdir(), "firefly-cloudflare-import-"));
    temporaryDirectories.push(directory);
    const migrationsDirectory = resolve(process.cwd(), "database/migrations");
    const migrationSql = readdirSync(migrationsDirectory)
      .filter((name) => /^\d+.*\.sql$/u.test(name))
      .sort()
      .map((name) => readFileSync(join(migrationsDirectory, name), "utf8"))
      .join("\n");
    const d1Path = join(directory, "d1.sql");
    const keyListPath = join(directory, "kv-key-list.json");
    const valuesPath = join(directory, "kv-values.json");
    const outputPath = join(directory, "firefly-imported.sqlite");
    writeFileSync(d1Path, `${migrationSql}\nINSERT INTO accounts (
      id, status, created_at, updated_at
    ) VALUES ('imported-account', 'active', '2026-09-12T00:00:00.000Z', '2026-09-12T00:00:00.000Z');`);
    writeFileSync(keyListPath, JSON.stringify([
      { name: "runtime:config", metadata: { revision: 2 } },
      { name: "subscription:managed:main" },
    ]));
    writeFileSync(valuesPath, JSON.stringify({
      "runtime:config": '{"notice":{"enabled":false}}',
      "subscription:managed:main": { value: "vless://imported@example.com:443" },
    }));

    const dryRun = await importCloudflareBackup({
      d1SqlPath: d1Path,
      kvKeyListPath: keyListPath,
      kvValuesPath: valuesPath,
      outputPath,
      migrationsDirectory,
      dryRun: true,
    });
    expect(dryRun).toMatchObject({ dryRun: true, outputPath: null, kvEntries: 2 });
    expect(existsSync(outputPath)).toBe(false);

    const report = await importCloudflareBackup({
      d1SqlPath: d1Path,
      kvKeyListPath: keyListPath,
      kvValuesPath: valuesPath,
      outputPath,
      migrationsDirectory,
    });
    expect(report).toMatchObject({
      dryRun: false,
      outputPath,
      kvEntries: 2,
      integrityCheck: "ok",
      tableRows: { accounts: 1 },
    });
    const imported = new BetterSqlite3(outputPath, { readonly: true });
    expect(imported.prepare("SELECT value FROM node_kv WHERE key = ?").get("subscription:managed:main"))
      .toEqual({ value: "vless://imported@example.com:443" });
    expect(imported.prepare("SELECT COUNT(*) AS count FROM node_schema_migrations").get())
      .toEqual({ count: 6 });
    imported.close();
  });
});
