export interface Env {
  DB: D1Database;
  CONFIG: KVNamespace;
  ASSETS: Fetcher;
  DEVICE_ENROLL_IP_RATE_LIMITER?: RateLimit;
  DEVICE_ENROLL_DEVICE_RATE_LIMITER?: RateLimit;
  SUBSCRIPTION_DEVICE_RATE_LIMITER?: RateLimit;
  ROTATE_DEVICE_RATE_LIMITER?: RateLimit;
  ADMIN_TOKEN: string;
  AI_API_KEY?: string;
  ADMIN_ROUTE: string;
  APP_ENV: string;
  AI_MODEL?: string;
}
