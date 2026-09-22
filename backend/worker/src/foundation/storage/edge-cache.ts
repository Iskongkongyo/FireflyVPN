const CACHE_ORIGIN = "https://firefly-worker-cache.invalid";

function defaultCache(): Cache | null {
  return (globalThis as unknown as {
    caches?: { default?: Cache };
  }).caches?.default ?? null;
}

function cacheRequest(key: string): Request {
  return new Request(`${CACHE_ORIGIN}/${encodeURIComponent(key)}`);
}

export interface EdgeCachedText {
  value: string;
  expiresAt: number;
}

export async function readEdgeCachedText(key: string): Promise<EdgeCachedText | null> {
  const cache = defaultCache();
  if (cache === null) return null;
  try {
    const response = await cache.match(cacheRequest(key));
    if (response === undefined) return null;
    const expiresAt = Number(response.headers.get("X-Firefly-Expires-At"));
    if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) return null;
    return { value: await response.text(), expiresAt };
  } catch {
    return null;
  }
}

export async function writeEdgeCachedText(
  key: string,
  value: string,
  ttlSeconds: number,
): Promise<void> {
  const cache = defaultCache();
  if (cache === null) return;
  try {
    const normalizedTtl = Math.max(1, Math.floor(ttlSeconds));
    await cache.put(cacheRequest(key), new Response(value, {
      headers: {
        "Cache-Control": `public, max-age=${normalizedTtl}`,
        "Content-Type": "text/plain; charset=utf-8",
        "X-Firefly-Expires-At": String(Date.now() + normalizedTtl * 1_000),
      },
    }));
  } catch {
    // Edge cache is an optimization only. Never fail an API request because a
    // local cache entry could not be stored.
  }
}
