export interface Env {
  DB: Database;
  CONFIG: KeyValueStore;
  ASSETS: AssetFetcher;
  ADMIN_TOKEN: string;
  AI_API_KEY?: string;
  ADMIN_ROUTE: string;
  APP_ENV: string;
  AI_MODEL?: string;
}
