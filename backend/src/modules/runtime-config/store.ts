import type { Env } from "../../app/env";
import {
  DEFAULT_RUNTIME_CONFIG,
  normalizeRuntimeConfig,
  type RuntimeConfig,
} from "./schema";

export const RUNTIME_CONFIG_KEY = "runtime:config";

export async function readRuntimeConfig(env: Env): Promise<RuntimeConfig> {
  const stored = await env.CONFIG.get(RUNTIME_CONFIG_KEY, "json");
  return stored === null
    ? structuredClone(DEFAULT_RUNTIME_CONFIG)
    : normalizeRuntimeConfig(stored);
}
