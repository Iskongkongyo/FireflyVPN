import BetterSqlite3, { type Database as SqliteDatabase } from "better-sqlite3";
import {
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
} from "node:fs";
import { basename, dirname, resolve } from "node:path";

import { NodeKvStore } from "./kv";

const CLOUDFLARE_SCHEMA_MIGRATIONS = [
  "0001_bootstrap.sql",
  "0002_anonymous_accounts.sql",
  "0003_subscription_notes.sql",
  "0004_audit_ip_address.sql",
  "0005_admin_jwt.sql",
  "0006_d1_write_optimization.sql",
] as const;

const REQUIRED_SCHEMA: Record<string, string[]> = {
  accounts: ["id", "status", "display_name", "created_at", "updated_at", "deleted_at"],
  devices: ["id", "account_id", "status", "public_key_spki", "token_hash"],
  subscription_sources: ["id", "source_type", "enabled", "updated_at", "note"],
  usage_sessions: ["device_id", "session_id", "account_id"],
  usage_daily: ["account_id", "day", "upload_bytes", "download_bytes"],
  usage_monthly: ["account_id", "month", "upload_bytes", "download_bytes"],
  crypto_rate_limits: ["scope", "identity_hash", "window_start", "request_count"],
  crypto_replay_nonces: ["device_id", "challenge_hash", "expires_at"],
  admin_audit_logs: ["id", "action", "ip_address", "created_at"],
  admin_security_settings: ["id", "jwt_secret", "jwt_secret_source"],
  admin_revoked_sessions: ["jti", "expires_at", "revoked_at"],
};

const REPORT_TABLES = [
  "accounts",
  "devices",
  "subscription_sources",
  "usage_sessions",
  "usage_daily",
  "usage_monthly",
  "admin_audit_logs",
] as const;

interface KvKeyDescription {
  name: string;
  expiration?: number;
  metadata?: unknown;
}

interface KvValueDescription {
  value: unknown;
  expiration?: number;
  metadata?: unknown;
}

export interface CloudflareImportOptions {
  d1SqlPath: string;
  kvKeyListPath: string;
  kvValuesPath: string;
  outputPath: string;
  migrationsDirectory: string;
  dryRun?: boolean;
}

export interface CloudflareImportReport {
  outputPath: string | null;
  dryRun: boolean;
  tableRows: Record<string, number>;
  kvEntries: number;
  expiredKvEntries: number;
  integrityCheck: string;
}

function readJson(path: string): unknown {
  try {
    return JSON.parse(readFileSync(resolve(path), "utf8"));
  } catch (error) {
    throw new Error(`Cannot parse JSON file ${resolve(path)}`, { cause: error });
  }
}

function record(value: unknown, description: string): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`${description} must be a JSON object`);
  }
  return value as Record<string, unknown>;
}

function readKeyList(path: string): KvKeyDescription[] {
  const parsed = readJson(path);
  if (!Array.isArray(parsed)) throw new Error("KV key list must be a JSON array");
  const names = new Set<string>();
  return parsed.map((item, index) => {
    const entry = record(item, `KV key list item ${index}`);
    if (typeof entry.name !== "string" || entry.name.length === 0) {
      throw new Error(`KV key list item ${index} has no valid name`);
    }
    if (names.has(entry.name)) throw new Error(`Duplicate KV key: ${entry.name}`);
    names.add(entry.name);
    const expiration = entry.expiration;
    if (expiration !== undefined && (!Number.isSafeInteger(expiration) || typeof expiration !== "number" || expiration <= 0)) {
      throw new Error(`KV key ${entry.name} has an invalid expiration`);
    }
    return {
      name: entry.name,
      expiration,
      metadata: entry.metadata,
    };
  });
}

function readValueMap(path: string): Record<string, unknown> {
  return record(readJson(path), "KV values");
}

function valueDescription(value: unknown): KvValueDescription {
  if (value !== null && typeof value === "object" && !Array.isArray(value)) {
    const candidate = value as Record<string, unknown>;
    const wrapperKeys = Object.keys(candidate);
    if (
      Object.hasOwn(candidate, "value")
      && wrapperKeys.every((key) => ["value", "metadata", "expiration"].includes(key))
    ) {
      return {
        value: candidate.value,
        metadata: candidate.metadata,
        expiration: candidate.expiration as number | undefined,
      };
    }
  }
  return { value };
}

function textValue(key: string, value: unknown): string {
  if (typeof value === "string") return value;
  if (value === null || value === undefined) throw new Error(`KV key ${key} has no value`);
  return JSON.stringify(value);
}

function validateSchema(database: SqliteDatabase): void {
  for (const [table, requiredColumns] of Object.entries(REQUIRED_SCHEMA)) {
    const exists = database.prepare(`
      SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?
    `).get(table);
    if (!exists) throw new Error(`D1 export is missing required table: ${table}`);
    const columns = new Set(
      database.prepare(`PRAGMA table_info("${table}")`).all()
        .map((row) => (row as { name: string }).name),
    );
    const missing = requiredColumns.filter((column) => !columns.has(column));
    if (missing.length > 0) {
      throw new Error(`D1 table ${table} is missing columns: ${missing.join(", ")}`);
    }
  }
}

function markCloudflareMigrationsApplied(database: SqliteDatabase, migrationsDirectory: string): void {
  const available = new Set(readdirSync(resolve(migrationsDirectory)));
  for (const migration of CLOUDFLARE_SCHEMA_MIGRATIONS) {
    if (!available.has(migration)) throw new Error(`Missing local migration file: ${migration}`);
  }
  database.exec(`
    CREATE TABLE IF NOT EXISTS node_schema_migrations (
      name TEXT PRIMARY KEY NOT NULL,
      applied_at TEXT NOT NULL
    )
  `);
  const insert = database.prepare(`
    INSERT OR IGNORE INTO node_schema_migrations (name, applied_at) VALUES (?, ?)
  `);
  const now = new Date().toISOString();
  for (const migration of CLOUDFLARE_SCHEMA_MIGRATIONS) insert.run(migration, now);
}

function removeImportFiles(path: string): void {
  for (const suffix of ["", "-shm", "-wal"]) rmSync(`${path}${suffix}`, { force: true });
}

export async function importCloudflareBackup(
  options: CloudflareImportOptions,
): Promise<CloudflareImportReport> {
  const outputPath = resolve(options.outputPath);
  if (existsSync(outputPath)) {
    throw new Error(`Output database already exists; refusing to overwrite: ${outputPath}`);
  }
  mkdirSync(dirname(outputPath), { recursive: true });
  const temporaryPath = resolve(
    dirname(outputPath),
    `.${basename(outputPath)}.importing-${process.pid}-${Date.now()}`,
  );
  const keyList = readKeyList(options.kvKeyListPath);
  const values = readValueMap(options.kvValuesPath);
  let database: SqliteDatabase | null = null;

  try {
    database = new BetterSqlite3(temporaryPath);
    database.pragma("busy_timeout = 5000");
    database.exec(readFileSync(resolve(options.d1SqlPath), "utf8"));
    if (database.inTransaction) database.exec("COMMIT");
    database.pragma("foreign_keys = ON");
    validateSchema(database);
    const foreignKeyViolations = database.pragma("foreign_key_check") as unknown[];
    if (foreignKeyViolations.length > 0) {
      throw new Error(`D1 export contains ${foreignKeyViolations.length} foreign-key violation(s)`);
    }
    markCloudflareMigrationsApplied(database, options.migrationsDirectory);

    const kv = new NodeKvStore(database).asKeyValueStore();
    const listedNames = new Set(keyList.map((entry) => entry.name));
    for (const key of Object.keys(values)) {
      if (!listedNames.has(key)) keyList.push({ name: key });
    }
    let expiredKvEntries = 0;
    const nowSeconds = Math.floor(Date.now() / 1_000);
    for (const entry of keyList) {
      if (!Object.hasOwn(values, entry.name)) {
        throw new Error(`KV values file is missing key: ${entry.name}`);
      }
      const exported = valueDescription(values[entry.name]);
      const expiration = entry.expiration ?? exported.expiration;
      if (expiration !== undefined && (!Number.isSafeInteger(expiration) || expiration <= 0)) {
        throw new Error(`KV key ${entry.name} has an invalid expiration`);
      }
      if (expiration !== undefined && expiration <= nowSeconds) expiredKvEntries += 1;
      await kv.put(entry.name, textValue(entry.name, exported.value), {
        expiration,
        metadata: entry.metadata ?? exported.metadata,
      });
    }

    const tableRows = Object.fromEntries(REPORT_TABLES.map((table) => {
      const row = database!.prepare(`SELECT COUNT(*) AS count FROM "${table}"`).get() as { count: number };
      return [table, Number(row.count)];
    }));
    const kvCount = database.prepare("SELECT COUNT(*) AS count FROM node_kv").get() as { count: number };
    const integrity = database.pragma("integrity_check", { simple: true });
    if (integrity !== "ok") throw new Error(`SQLite integrity check failed: ${String(integrity)}`);
    database.pragma("wal_checkpoint(TRUNCATE)");
    database.close();
    database = null;

    const report: CloudflareImportReport = {
      outputPath: options.dryRun ? null : outputPath,
      dryRun: options.dryRun === true,
      tableRows,
      kvEntries: Number(kvCount.count),
      expiredKvEntries,
      integrityCheck: String(integrity),
    };
    if (options.dryRun) removeImportFiles(temporaryPath);
    else renameSync(temporaryPath, outputPath);
    return report;
  } catch (error) {
    if (database?.open) database.close();
    removeImportFiles(temporaryPath);
    throw error;
  }
}
