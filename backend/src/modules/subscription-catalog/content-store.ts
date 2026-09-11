import { AppError } from "../../foundation/http/errors";
import {
  readEdgeCachedText,
  writeEdgeCachedText,
} from "../../foundation/storage/edge-cache";
import type { SubscriptionSourceRecord } from "./models";
import { fetchExternalSource } from "./source-fetcher";

const MANAGED_EDGE_CACHE_SECONDS = 300;
const EXTERNAL_KV_PERSIST_SECONDS = 3_600;

interface ContentCacheEntry {
  content: string;
  expiresAt: number;
}

interface ExternalCacheMetadata {
  fetchedAt: number;
}

const memoryCaches = new WeakMap<KVNamespace, Map<string, ContentCacheEntry>>();
const refreshes = new WeakMap<KVNamespace, Map<string, Promise<string>>>();

function contentCacheKey(source: SubscriptionSourceRecord): string {
  return `subscription-content:${source.sourceType}:${source.id}:${source.updatedAt}`;
}

function cacheMap(config: KVNamespace): Map<string, ContentCacheEntry> {
  let cache = memoryCaches.get(config);
  if (cache === undefined) {
    cache = new Map();
    memoryCaches.set(config, cache);
  }
  return cache;
}

function remember(
  config: KVNamespace,
  key: string,
  content: string,
  ttlSeconds: number,
): string {
  cacheMap(config).set(key, {
    content,
    expiresAt: Date.now() + Math.max(1, ttlSeconds) * 1_000,
  });
  return content;
}

function readRemembered(config: KVNamespace, key: string): string | null {
  const cache = cacheMap(config);
  const entry = cache.get(key);
  if (entry === undefined) return null;
  if (entry.expiresAt <= Date.now()) {
    cache.delete(key);
    return null;
  }
  return entry.content;
}

function validFetchedAt(value: unknown): number | null {
  if (
    value !== null
    && typeof value === "object"
    && Number.isSafeInteger((value as ExternalCacheMetadata).fetchedAt)
  ) {
    return (value as ExternalCacheMetadata).fetchedAt;
  }
  return null;
}

function invalidateMemoryCache(config: KVNamespace, sourceId: string): void {
  const cache = memoryCaches.get(config);
  if (cache === undefined) return;
  for (const key of cache.keys()) {
    if (key.startsWith("subscription-content:") && key.includes(`:${sourceId}:`)) {
      cache.delete(key);
    }
  }
}

export function managedContentKey(sourceId: string): string {
  return `subscription:managed:${sourceId}`;
}

export function externalCacheKey(sourceId: string): string {
  return `subscription:cache:${sourceId}`;
}

export function externalCacheMetaKey(sourceId: string): string {
  return `subscription:cache-meta:${sourceId}`;
}

export async function readSubscriptionContent(
  source: SubscriptionSourceRecord,
  config: KVNamespace,
  timeoutMilliseconds: number,
  forceRefresh = false,
): Promise<string> {
  const cacheKey = contentCacheKey(source);
  if (!forceRefresh) {
    const remembered = readRemembered(config, cacheKey);
    if (remembered !== null) return remembered;
    const edgeCached = await readEdgeCachedText(cacheKey);
    if (edgeCached !== null) {
      const remainingTtl = Math.max(1, Math.ceil((edgeCached.expiresAt - Date.now()) / 1_000));
      return remember(config, cacheKey, edgeCached.value, remainingTtl);
    }
  }

  if (source.sourceType === "managed") {
    const content = await config.get(managedContentKey(source.id));
    if (content === null) throw new AppError("subscription_unavailable", 503);
    remember(config, cacheKey, content, MANAGED_EDGE_CACHE_SECONDS);
    await writeEdgeCachedText(cacheKey, content, MANAGED_EDGE_CACHE_SECONDS);
    return content;
  }

  if (source.sourceUrl === null) throw new AppError("subscription_unavailable", 503);
  let persistedAt: number | null = null;
  if (!forceRefresh) {
    const cached = await config.getWithMetadata<ExternalCacheMetadata>(externalCacheKey(source.id));
    persistedAt = validFetchedAt(cached.metadata);
    if (persistedAt === null && cached.value !== null) {
      // Backwards compatibility for cache entries written before metadata was
      // stored on the content key itself.
      persistedAt = validFetchedAt(await config.get<ExternalCacheMetadata>(
        externalCacheMetaKey(source.id),
        "json",
      ));
    }
    const now = Math.floor(Date.now() / 1000);
    if (
      cached.value !== null
      && persistedAt !== null
      && now - persistedAt < source.cacheTtlSeconds
    ) {
      const remainingTtl = Math.max(1, source.cacheTtlSeconds - (now - persistedAt));
      remember(config, cacheKey, cached.value, remainingTtl);
      await writeEdgeCachedText(cacheKey, cached.value, remainingTtl);
      return cached.value;
    }
  }

  let sourceRefreshes = refreshes.get(config);
  if (sourceRefreshes === undefined) {
    sourceRefreshes = new Map();
    refreshes.set(config, sourceRefreshes);
  }
  const existingRefresh = sourceRefreshes.get(source.id);
  if (existingRefresh !== undefined) return existingRefresh;

  const refresh = (async (): Promise<string> => {
    const content = await fetchExternalSource(source.sourceUrl!, timeoutMilliseconds);
    const fetchedAt = Math.floor(Date.now() / 1000);
    remember(config, cacheKey, content, source.cacheTtlSeconds);
    await writeEdgeCachedText(cacheKey, content, source.cacheTtlSeconds);

    if (
      forceRefresh
      || persistedAt === null
      || fetchedAt - persistedAt >= EXTERNAL_KV_PERSIST_SECONDS
    ) {
      try {
        await config.put(externalCacheKey(source.id), content, {
          metadata: { fetchedAt } satisfies ExternalCacheMetadata,
        });
      } catch (error) {
        // KV is only the durable cache for external sources. A successful
        // origin fetch remains usable even when the daily KV write quota is
        // temporarily unavailable.
        console.error(JSON.stringify({
          event: "subscription_cache_write_failed",
          sourceId: source.id,
          message: error instanceof Error ? error.message : String(error),
        }));
      }
    }
    return content;
  })();
  sourceRefreshes.set(source.id, refresh);
  try {
    return await refresh;
  } finally {
    sourceRefreshes.delete(source.id);
  }
}

export async function writeManagedSubscriptionContent(
  config: KVNamespace,
  sourceId: string,
  content: string,
): Promise<void> {
  await config.put(managedContentKey(sourceId), content);
  invalidateMemoryCache(config, sourceId);
}

export async function clearSourceContent(config: KVNamespace, sourceId: string): Promise<void> {
  invalidateMemoryCache(config, sourceId);
  await Promise.all([
    config.delete(managedContentKey(sourceId)),
    config.delete(externalCacheKey(sourceId)),
    config.delete(externalCacheMetaKey(sourceId)),
  ]);
}
