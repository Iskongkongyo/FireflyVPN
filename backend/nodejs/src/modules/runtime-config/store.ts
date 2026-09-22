import type { Env } from "../../app/env";
import {
  DEFAULT_RUNTIME_CONFIG,
  normalizeRuntimeConfig,
  type RuntimeConfig,
} from "./schema";
export const RUNTIME_CONFIG_KEY = "runtime:config";
const RUNTIME_CONFIG_CACHE_SECONDS = 60;

interface CachedRuntimeConfig {
  expiresAt: number;
  value: RuntimeConfig;
}

const memoryCache = new WeakMap<KeyValueStore, CachedRuntimeConfig>();

function remember(config: KeyValueStore, value: RuntimeConfig, nowMilliseconds: number): RuntimeConfig {
  memoryCache.set(config, {
    expiresAt: nowMilliseconds + RUNTIME_CONFIG_CACHE_SECONDS * 1_000,
    value,
  });
  return value;
}

export async function readRuntimeConfig(
  env: Env,
  nowMilliseconds = Date.now(),
): Promise<RuntimeConfig> {
  const cached = memoryCache.get(env.CONFIG);
  if (cached !== undefined && cached.expiresAt > nowMilliseconds) return cached.value;

  const stored = await env.CONFIG.get(RUNTIME_CONFIG_KEY, "json");
  const value = stored === null
    ? structuredClone(DEFAULT_RUNTIME_CONFIG)
    : normalizeRuntimeConfig(stored);
  return remember(env.CONFIG, value, nowMilliseconds);
}

export async function writeRuntimeConfig(env: Env, value: RuntimeConfig): Promise<void> {
  await env.CONFIG.put(RUNTIME_CONFIG_KEY, JSON.stringify(value));
  remember(env.CONFIG, value, Date.now());
}
