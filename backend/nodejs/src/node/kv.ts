import type { Database as SqliteDatabase } from "better-sqlite3";

interface StoredKvRow {
  value: string;
  metadataJson: string | null;
}

interface PutOptions {
  expiration?: number;
  expirationTtl?: number;
  metadata?: unknown;
}

type KvGetType = "text" | "json" | "arrayBuffer" | "stream";

export class NodeKvStore {
  constructor(private readonly database: SqliteDatabase) {
    database.exec(`
      CREATE TABLE IF NOT EXISTS node_kv (
        key TEXT PRIMARY KEY NOT NULL,
        value TEXT NOT NULL,
        metadata_json TEXT,
        expires_at INTEGER
      )
    `);
  }

  private read(key: string): StoredKvRow | null {
    const row = this.database.prepare(`
      SELECT value, metadata_json AS metadataJson
      FROM node_kv
      WHERE key = ? AND (expires_at IS NULL OR expires_at > ?)
    `).get(key, Math.floor(Date.now() / 1_000)) as StoredKvRow | undefined;
    return row ?? null;
  }

  async get<T = string>(key: string, type: KvGetType = "text"): Promise<T | null> {
    const row = this.read(key);
    if (row === null) return null;
    if (type === "json") return JSON.parse(row.value) as T;
    if (type === "arrayBuffer") {
      const bytes = new TextEncoder().encode(row.value);
      return bytes.buffer as T;
    }
    if (type === "stream") {
      const bytes = new TextEncoder().encode(row.value);
      return new ReadableStream({
        start(controller) {
          controller.enqueue(bytes);
          controller.close();
        },
      }) as T;
    }
    return row.value as T;
  }

  async getWithMetadata<Metadata = unknown>(key: string): Promise<{
    value: string | null;
    metadata: Metadata | null;
    cacheStatus: null;
  }> {
    const row = this.read(key);
    return {
      value: row?.value ?? null,
      metadata: row?.metadataJson ? JSON.parse(row.metadataJson) as Metadata : null,
      cacheStatus: null,
    };
  }

  async put(key: string, value: string | ArrayBuffer | ArrayBufferView, options: PutOptions = {}): Promise<void> {
    let text: string;
    if (typeof value === "string") text = value;
    else if (value instanceof ArrayBuffer) text = new TextDecoder().decode(value);
    else text = new TextDecoder().decode(value);
    const now = Math.floor(Date.now() / 1_000);
    const expiresAt = options.expiration
      ?? (options.expirationTtl === undefined ? null : now + options.expirationTtl);
    this.database.prepare(`
      INSERT INTO node_kv (key, value, metadata_json, expires_at)
      VALUES (?, ?, ?, ?)
      ON CONFLICT(key) DO UPDATE SET
        value = excluded.value,
        metadata_json = excluded.metadata_json,
        expires_at = excluded.expires_at
    `).run(
      key,
      text,
      options.metadata === undefined ? null : JSON.stringify(options.metadata),
      expiresAt,
    );
  }

  async delete(key: string): Promise<void> {
    this.database.prepare("DELETE FROM node_kv WHERE key = ?").run(key);
  }

  asKeyValueStore(): KeyValueStore {
    return this as unknown as KeyValueStore;
  }
}
