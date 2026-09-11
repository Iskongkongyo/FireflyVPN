import type { Env } from "../../app/env";
import {
  DEFAULT_RUNTIME_CONFIG,
  normalizeRuntimeConfig,
  type RuntimeConfig,
} from "./schema";
import {
  readEdgeCachedText,
  writeEdgeCachedText,
} from "../../foundation/storage/edge-cache";

export const RUNTIME_CONFIG_KEY = "runtime:config";
const RUNTIME_CONFIG_CACHE_SECONDS = 60;
const RUNTIME_CONFIG_EDGE_KEY = "runtime-config:v1";

interface CachedRuntimeConfig {
  expiresAt: number;
  value: RuntimeConfig;
}

const memoryCache = new WeakMap<KVNamespace, CachedRuntimeConfig>();

function remember(config: KVNamespace, value: RuntimeConfig, nowMilliseconds: number): RuntimeConfig {
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

  const edgeCached = await readEdgeCachedText(RUNTIME_CONFIG_EDGE_KEY);
  if (edgeCached !== null) {
    try {
      const value = normalizeRuntimeConfig(JSON.parse(edgeCached.value));
      memoryCache.set(env.CONFIG, { expiresAt: edgeCached.expiresAt, value });
      return value;
    } catch {
      // Ignore malformed ephemeral cache data and fall through to KV.
    }
  }

  const stored = await env.CONFIG.get(RUNTIME_CONFIG_KEY, "json");
  const value = stored === null
    ? structuredClone(DEFAULT_RUNTIME_CONFIG)
    : normalizeRuntimeConfig(stored);
  remember(env.CONFIG, value, nowMilliseconds);
  await writeEdgeCachedText(
    RUNTIME_CONFIG_EDGE_KEY,
    JSON.stringify(value),
    RUNTIME_CONFIG_CACHE_SECONDS,
  );
  return value;
}

export async function writeRuntimeConfig(env: Env, value: RuntimeConfig): Promise<void> {
  await env.CONFIG.put(RUNTIME_CONFIG_KEY, JSON.stringify(value));
  remember(env.CONFIG, value, Date.now());
  await writeEdgeCachedText(
    RUNTIME_CONFIG_EDGE_KEY,
    JSON.stringify(value),
    RUNTIME_CONFIG_CACHE_SECONDS,
  );
}
