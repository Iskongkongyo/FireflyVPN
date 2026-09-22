import BetterSqlite3, { type Database as SqliteDatabase } from "better-sqlite3";
import { mkdirSync, readFileSync, readdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { performance } from "node:perf_hooks";

type BindValue = null | number | string | ArrayBuffer | ArrayBufferView;

function normalizeBindValue(value: unknown): null | number | string | Buffer {
  if (value === null) return null;
  if (typeof value === "boolean") return value ? 1 : 0;
  if (typeof value === "number" || typeof value === "string") return value;
  if (value instanceof ArrayBuffer) return Buffer.from(value);
  if (ArrayBuffer.isView(value)) {
    return Buffer.from(value.buffer, value.byteOffset, value.byteLength);
  }
  throw new TypeError(`Unsupported SQLite bind value: ${typeof value}`);
}

function resultMeta(
  startedAt: number,
  changes = 0,
  lastRowId = 0,
): DatabaseMeta & Record<string, unknown> {
  const duration = performance.now() - startedAt;
  return {
    changed_db: true,
    changes,
    duration,
    last_row_id: lastRowId,
    rows_read: 0,
    rows_written: changes,
    size_after: 0,
  };
}

class NodePreparedStatement {
  private bindings: Array<null | number | string | Buffer> = [];
  private readonly compiledSql: string;

  constructor(
    private readonly database: SqliteDatabase,
    sql: string,
  ) {
    // D1 uses indexed parameters (?1, ?2, ...). better-sqlite3 treats those as
    // named parameters, so translate them explicitly while preserving repeats
    // and out-of-order indexes.
    this.compiledSql = sql.replace(/\?(\d+)/gu, "@p$1");
  }

  private parameters(): Record<string, null | number | string | Buffer> {
    return Object.fromEntries(this.bindings.map((value, index) => [`p${index + 1}`, value]));
  }

  bind(...values: BindValue[]): NodePreparedStatement {
    this.bindings = values.map(normalizeBindValue);
    return this;
  }

  async first<T = Record<string, unknown>>(columnName?: string): Promise<T | null> {
    const row = this.database.prepare(this.compiledSql).get(this.parameters()) as Record<string, unknown> | undefined;
    if (row === undefined) return null;
    if (columnName !== undefined) return (row[columnName] as T | undefined) ?? null;
    return row as T;
  }

  async all<T = Record<string, unknown>>(): Promise<DatabaseResult<T>> {
    const startedAt = performance.now();
    const results = this.database.prepare(this.compiledSql).all(this.parameters()) as T[];
    return {
      success: true,
      results,
      meta: resultMeta(startedAt),
    };
  }

  async run<T = Record<string, unknown>>(): Promise<DatabaseResult<T>> {
    return this.runSync<T>();
  }

  runSync<T = Record<string, unknown>>(): DatabaseResult<T> {
    const startedAt = performance.now();
    const statement = this.database.prepare(this.compiledSql);
    if (statement.reader) {
      const results = statement.all(this.parameters()) as T[];
      return { success: true, results, meta: resultMeta(startedAt) };
    }
    const outcome = statement.run(this.parameters());
    return {
      success: true,
      results: [],
      meta: resultMeta(startedAt, outcome.changes, Number(outcome.lastInsertRowid)),
    };
  }

  async raw<T = unknown[]>(options?: { columnNames?: boolean }): Promise<T[]> {
    const statement = this.database.prepare(this.compiledSql).raw(true);
    const rows = statement.all(this.parameters()) as T[];
    if (!options?.columnNames) return rows;
    return [statement.columns().map((column) => column.name) as T, ...rows];
  }
}

export class NodeDatabase {
  readonly sqlite: SqliteDatabase;

  constructor(filename: string) {
    if (filename !== ":memory:") mkdirSync(dirname(resolve(filename)), { recursive: true });
    this.sqlite = new BetterSqlite3(filename);
    this.sqlite.pragma("foreign_keys = ON");
    this.sqlite.pragma("busy_timeout = 5000");
    if (filename !== ":memory:") this.sqlite.pragma("journal_mode = WAL");
  }

  prepare(sql: string): NodePreparedStatement {
    return new NodePreparedStatement(this.sqlite, sql);
  }

  async batch<T = unknown>(statements: PreparedStatement[]): Promise<DatabaseResult<T>[]> {
    const execute = this.sqlite.transaction(() => statements.map((statement) => {
      if (!(statement instanceof NodePreparedStatement)) {
        throw new TypeError("A batch can only contain statements from the same NodeDatabase");
      }
      return statement.runSync<T>();
    }));
    return execute();
  }

  async exec(sql: string): Promise<DatabaseExecResult> {
    const startedAt = performance.now();
    this.sqlite.exec(sql);
    return { count: 0, duration: performance.now() - startedAt };
  }

  dump(): Promise<ArrayBuffer> {
    throw new Error("Database dump is not supported by the Node.js adapter");
  }

  close(): void {
    this.sqlite.close();
  }

  asDatabase(): Database {
    return this as unknown as Database;
  }
}

export function applyMigrations(database: NodeDatabase, migrationsDirectory: string): string[] {
  const directory = resolve(migrationsDirectory);
  database.sqlite.exec(`
    CREATE TABLE IF NOT EXISTS node_schema_migrations (
      name TEXT PRIMARY KEY NOT NULL,
      applied_at TEXT NOT NULL
    )
  `);
  const applied = database.sqlite
    .prepare("SELECT name FROM node_schema_migrations")
    .all()
    .map((row) => (row as { name: string }).name);
  const appliedSet = new Set(applied);
  const files = readdirSync(directory)
    .filter((name) => /^\d+.*\.sql$/u.test(name))
    .sort((left, right) => left.localeCompare(right));
  const completed: string[] = [];

  for (const name of files) {
    if (appliedSet.has(name)) continue;
    const sql = readFileSync(resolve(directory, name), "utf8");
    const migrate = database.sqlite.transaction(() => {
      database.sqlite.exec(sql);
      database.sqlite.prepare(`
        INSERT INTO node_schema_migrations (name, applied_at) VALUES (?, ?)
      `).run(name, new Date().toISOString());
    });
    migrate();
    completed.push(name);
  }
  return completed;
}
