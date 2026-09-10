import type { Env } from "../../app/env";

export function database(env: Env): D1Database {
  return env.DB;
}
