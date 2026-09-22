type StorageBindValue = null | number | string | ArrayBuffer | ArrayBufferView;

interface DatabaseMeta extends Record<string, unknown> {
  changes: number;
  duration: number;
  last_row_id: number;
  rows_read: number;
  rows_written: number;
}

interface DatabaseResult<T = Record<string, unknown>> {
  success: boolean;
  results: T[];
  meta: DatabaseMeta;
}

interface PreparedStatement {
  bind(...values: StorageBindValue[]): PreparedStatement;
  first<T = Record<string, unknown>>(columnName?: string): Promise<T | null>;
  all<T = Record<string, unknown>>(): Promise<DatabaseResult<T>>;
  run<T = Record<string, unknown>>(): Promise<DatabaseResult<T>>;
  raw<T = unknown[]>(options?: { columnNames?: boolean }): Promise<T[]>;
}

interface DatabaseExecResult {
  count: number;
  duration: number;
}

interface Database {
  prepare(sql: string): PreparedStatement;
  batch<T = Record<string, unknown>>(statements: PreparedStatement[]): Promise<DatabaseResult<T>[]>;
  exec(sql: string): Promise<DatabaseExecResult>;
}

type KeyValueGetType = "text" | "json" | "arrayBuffer" | "stream";

interface KeyValuePutOptions {
  expiration?: number;
  expirationTtl?: number;
  metadata?: unknown;
}

interface KeyValueStore {
  get<T = string>(key: string, type?: KeyValueGetType): Promise<T | null>;
  getWithMetadata<Metadata = unknown>(key: string): Promise<{
    value: string | null;
    metadata: Metadata | null;
    cacheStatus: string | null;
  }>;
  put(
    key: string,
    value: string | ArrayBuffer | ArrayBufferView,
    options?: KeyValuePutOptions,
  ): Promise<void>;
  delete(key: string): Promise<void>;
}

interface AssetFetcher {
  fetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response>;
}
