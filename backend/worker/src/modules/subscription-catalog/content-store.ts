import { AppError } from "../../foundation/http/errors";
import {
  readEdgeCachedText,
  writeEdgeCachedText,
} from "../../foundation/storage/edge-cache";
import { mergeSubscriptionContent } from "./merger";
import type { ExternalHealthOutcome, SubscriptionSourceRecord } from "./models";
import { fetchExternalSource } from "./source-fetcher";

const MANAGED_EDGE_CACHE_SECONDS = 300;
const EXTERNAL_KV_PERSIST_SECONDS = 3_600;

interface ContentCacheEntry {
  content: string;
  expiresAt: number;
}

interface ExternalCacheMetadata {
  fetchedAt: number;
  sourceUpdatedAt?: string;
  sourceUrl?: string;
}

interface ExternalContentResult {
  content: string;
  fallbackUsed: boolean;
  originAttempted: boolean;
  failure: unknown | null;
}

export type ExternalHealthReporter = (outcome: ExternalHealthOutcome) => Promise<void>;

const memoryCaches = new WeakMap<KVNamespace, Map<string, ContentCacheEntry>>();
const refreshes = new WeakMap<KVNamespace, Map<string, Promise<ExternalContentResult>>>();

function contentCacheKey(source: SubscriptionSourceRecord): string {
  return `subscription-content:${source.sourceType}:${source.id}:${source.updatedAt}`;
}

function managedCacheKey(source: SubscriptionSourceRecord): string {
  return `subscription-managed:${source.id}:${source.updatedAt}`;
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

function cacheMatchesSource(metadata: ExternalCacheMetadata | null, source: SubscriptionSourceRecord): boolean {
  return metadata?.sourceUrl !== undefined
    ? metadata.sourceUrl === source.sourceUrl
    : metadata?.sourceUpdatedAt === source.updatedAt;
}

function healthErrorCode(error: unknown): string {
  return error instanceof AppError ? error.code : "upstream_failed";
}

async function reportHealth(
  source: SubscriptionSourceRecord,
  reporter: ExternalHealthReporter | undefined,
  healthy: boolean,
  fallbackActive: boolean,
  error: unknown = null,
): Promise<void> {
  if (reporter === undefined) return;
  try {
    await reporter({
      healthy,
      fallbackActive,
      errorCode: healthy ? null : healthErrorCode(error),
      checkedAt: new Date().toISOString(),
    });
  } catch (reportError) {
    console.error(JSON.stringify({
      event: "subscription_health_write_failed",
      sourceId: source.id,
      message: reportError instanceof Error ? reportError.message : String(reportError),
    }));
  }
}

function invalidateMemoryCache(config: KVNamespace, sourceId: string): void {
  const cache = memoryCaches.get(config);
  if (cache === undefined) return;
  for (const key of cache.keys()) {
    if ((key.startsWith("subscription-content:") || key.startsWith("subscription-managed:"))
      && key.includes(`:${sourceId}:`)) {
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

async function readManagedContent(
  source: SubscriptionSourceRecord,
  config: KVNamespace,
): Promise<string | null> {
  const key = managedCacheKey(source);
  const remembered = readRemembered(config, key);
  if (remembered !== null) return remembered || null;
  const edgeCached = await readEdgeCachedText(key);
  if (edgeCached !== null) {
    const remainingTtl = Math.max(1, Math.ceil((edgeCached.expiresAt - Date.now()) / 1_000));
    return remember(config, key, edgeCached.value, remainingTtl) || null;
  }
  const content = await config.get(managedContentKey(source.id));
  remember(config, key, content ?? "", MANAGED_EDGE_CACHE_SECONDS);
  await writeEdgeCachedText(key, content ?? "", MANAGED_EDGE_CACHE_SECONDS);
  return content;
}

async function readExternalContent(
  source: SubscriptionSourceRecord,
  config: KVNamespace,
  timeoutMilliseconds: number,
  forceRefresh = false,
  reporter?: ExternalHealthReporter,
): Promise<ExternalContentResult> {
  const cacheKey = contentCacheKey(source);
  if (!forceRefresh) {
    const remembered = readRemembered(config, cacheKey);
    if (remembered !== null) {
      return { content: remembered, fallbackUsed: false, originAttempted: false, failure: null };
    }
    const edgeCached = await readEdgeCachedText(cacheKey);
    if (edgeCached !== null) {
      const remainingTtl = Math.max(1, Math.ceil((edgeCached.expiresAt - Date.now()) / 1_000));
      return {
        content: remember(config, cacheKey, edgeCached.value, remainingTtl),
        fallbackUsed: false,
        originAttempted: false,
        failure: null,
      };
    }
  }

  if (source.sourceUrl === null) throw new AppError("subscription_unavailable", 503);
  const cached = await config.getWithMetadata<ExternalCacheMetadata>(externalCacheKey(source.id));
  let persistedAt = validFetchedAt(cached.metadata);
  if (!cacheMatchesSource(cached.metadata, source)) persistedAt = null;
  const fallbackContent = cached.value !== null && persistedAt !== null ? cached.value : null;
  if (!forceRefresh) {
    const now = Math.floor(Date.now() / 1000);
    if (
      cached.value !== null
      && persistedAt !== null
      && now - persistedAt < source.cacheTtlSeconds
    ) {
      const remainingTtl = Math.max(1, source.cacheTtlSeconds - (now - persistedAt));
      remember(config, cacheKey, cached.value, remainingTtl);
      await writeEdgeCachedText(cacheKey, cached.value, remainingTtl);
      return { content: cached.value, fallbackUsed: false, originAttempted: false, failure: null };
    }
  }

  let sourceRefreshes = refreshes.get(config);
  if (sourceRefreshes === undefined) {
    sourceRefreshes = new Map();
    refreshes.set(config, sourceRefreshes);
  }
  const refreshKey = `${cacheKey}:${forceRefresh ? "force" : "normal"}`;
  const existingRefresh = sourceRefreshes.get(refreshKey);
  if (existingRefresh !== undefined) return existingRefresh;

  const refresh = (async (): Promise<ExternalContentResult> => {
    let content: string;
    try {
      content = await fetchExternalSource(source.sourceUrl!, timeoutMilliseconds);
    } catch (error) {
      if (source.externalHealthEnabled === 1 && !forceRefresh && fallbackContent !== null) {
        remember(config, cacheKey, fallbackContent, source.cacheTtlSeconds);
        await writeEdgeCachedText(cacheKey, fallbackContent, source.cacheTtlSeconds);
        return { content: fallbackContent, fallbackUsed: true, originAttempted: true, failure: error };
      }
      await reportHealth(source, reporter, false, fallbackContent !== null, error);
      throw error;
    }
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
          metadata: {
            fetchedAt,
            sourceUpdatedAt: source.updatedAt,
            sourceUrl: source.sourceUrl!,
          } satisfies ExternalCacheMetadata,
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
    return { content, fallbackUsed: false, originAttempted: true, failure: null };
  })();
  sourceRefreshes.set(refreshKey, refresh);
  try {
    return await refresh;
  } finally {
    sourceRefreshes.delete(refreshKey);
  }
}

export async function readSubscriptionContent(
  source: SubscriptionSourceRecord,
  config: KVNamespace,
  timeoutMilliseconds: number,
  forceRefresh = false,
  reporter?: ExternalHealthReporter,
): Promise<string> {
  const managed = await readManagedContent(source, config);
  if (source.sourceType === "managed") {
    if (managed !== null) return managed;
    throw new AppError("subscription_unavailable", 503);
  }
  if (source.sourceUrl === null) throw new AppError("subscription_unavailable", 503);
  const external = await readExternalContent(
    source,
    config,
    timeoutMilliseconds,
    forceRefresh,
    reporter,
  );
  try {
    const content = mergeSubscriptionContent(external.content, managed, source.mergeMode);
    if (external.originAttempted) {
      await reportHealth(
        source,
        reporter,
        !external.fallbackUsed,
        external.fallbackUsed,
        external.failure,
      );
    }
    return content;
  } catch (error) {
    if (external.originAttempted) await reportHealth(source, reporter, false, false, error);
    throw error;
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

export async function deleteManagedSubscriptionContent(
  config: KVNamespace,
  sourceId: string,
): Promise<void> {
  await config.delete(managedContentKey(sourceId));
  invalidateMemoryCache(config, sourceId);
}

export async function clearExternalSourceCache(
  config: KVNamespace,
  sourceId: string,
): Promise<void> {
  invalidateMemoryCache(config, sourceId);
  await Promise.all([
    config.delete(externalCacheKey(sourceId)),
    config.delete(externalCacheMetaKey(sourceId)),
  ]);
}

export async function clearSourceContent(config: KVNamespace, sourceId: string): Promise<void> {
  await Promise.all([
    deleteManagedSubscriptionContent(config, sourceId),
    clearExternalSourceCache(config, sourceId),
  ]);
}
