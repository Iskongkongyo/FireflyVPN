import { AppError } from "../../foundation/http/errors";
import type { SubscriptionSourceRecord } from "./models";
import { fetchExternalSource } from "./source-fetcher";

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
  if (source.sourceType === "managed") {
    const content = await config.get(managedContentKey(source.id));
    if (content === null) throw new AppError("subscription_unavailable", 503);
    return content;
  }

  if (source.sourceUrl === null) throw new AppError("subscription_unavailable", 503);
  if (!forceRefresh) {
    const metadata = await config.get<{ fetchedAt: number }>(
      externalCacheMetaKey(source.id),
      "json",
    );
    const now = Math.floor(Date.now() / 1000);
    if (
      metadata !== null
      && Number.isSafeInteger(metadata.fetchedAt)
      && now - metadata.fetchedAt < source.cacheTtlSeconds
    ) {
      const cached = await config.get(externalCacheKey(source.id));
      if (cached !== null) return cached;
    }
  }

  const content = await fetchExternalSource(source.sourceUrl, timeoutMilliseconds);
  const fetchedAt = Math.floor(Date.now() / 1000);
  await Promise.all([
    config.put(externalCacheKey(source.id), content),
    config.put(externalCacheMetaKey(source.id), JSON.stringify({ fetchedAt })),
  ]);
  return content;
}

export async function clearSourceContent(config: KVNamespace, sourceId: string): Promise<void> {
  await Promise.all([
    config.delete(managedContentKey(sourceId)),
    config.delete(externalCacheKey(sourceId)),
    config.delete(externalCacheMetaKey(sourceId)),
  ]);
}
