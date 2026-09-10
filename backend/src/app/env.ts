export interface Env {
  DB: D1Database;
  CONFIG: KVNamespace;
  ASSETS: Fetcher;
  ADMIN_TOKEN: string;
  AI_API_KEY?: string;
  ADMIN_ROUTE: string;
  APP_ENV: string;
  AI_MODEL?: string;
}
