import { afterEach, describe, expect, it, vi } from "vitest";

import {
  externalCacheKey,
  readSubscriptionContent,
} from "../../src/modules/subscription-catalog/content-store";
import type { SubscriptionSourceRecord } from "../../src/modules/subscription-catalog/models";

const source: SubscriptionSourceRecord = {
  id: "external-main",
  name: "External",
  note: null,
  sourceType: "external",
  sourceUrl: "https://example.com/subscription",
  enabled: 1,
  sortOrder: 0,
  cacheTtlSeconds: 300,
  createdAt: "2026-09-06T00:00:00.000Z",
  updatedAt: "2026-09-06T00:00:00.000Z",
};

function memoryKv(): {
  config: KVNamespace;
  stats: { gets: number; puts: number; deletes: number };
} {
  const values = new Map<string, string>();
  const metadata = new Map<string, unknown>();
  const stats = { gets: 0, puts: 0, deletes: 0 };
  const config = {
    get: async (key: string, type?: string) => {
      stats.gets += 1;
      const value = values.get(key) ?? null;
      return type === "json" && value !== null ? JSON.parse(value) : value;
    },
    getWithMetadata: async (key: string) => {
      stats.gets += 1;
      return {
        value: values.get(key) ?? null,
        metadata: metadata.get(key) ?? null,
      };
    },
    put: async (key: string, value: string, options?: { metadata?: unknown }) => {
      stats.puts += 1;
      values.set(key, value);
      if (options?.metadata !== undefined) metadata.set(key, options.metadata);
    },
    delete: async (key: string) => {
      stats.deletes += 1;
      values.delete(key);
      metadata.delete(key);
    },
  } as unknown as KVNamespace;
  return { config, stats };
}

describe("external subscription cache", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("uses a fresh KV cache and supports forced refresh", async () => {
    const { config, stats } = memoryKv();
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response("vless://first@example.com:443"))
      .mockResolvedValueOnce(new Response("vless://refreshed@example.com:443"));
    vi.stubGlobal("fetch", fetcher);

    await expect(readSubscriptionContent(source, config, 1_000))
      .resolves.toContain("first");
    await expect(readSubscriptionContent(source, config, 1_000))
      .resolves.toContain("first");
    expect(fetcher).toHaveBeenCalledTimes(1);

    await expect(readSubscriptionContent(source, config, 1_000, true))
      .resolves.toContain("refreshed");
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(stats.puts).toBe(2);
  });

  it("stores external content and refresh metadata in one KV write", async () => {
    const { config, stats } = memoryKv();
    vi.stubGlobal("fetch", vi.fn<typeof fetch>()
      .mockResolvedValue(new Response("vless://first@example.com:443")));

    await readSubscriptionContent(source, config, 1_000);

    expect(stats.puts).toBe(1);
    const cached = await config.getWithMetadata<{ fetchedAt: number }>(externalCacheKey(source.id));
    expect(cached.value).toContain("first");
    expect(cached.metadata?.fetchedAt).toEqual(expect.any(Number));
  });

  it("keeps five-minute origin freshness without rewriting KV more than hourly", async () => {
    const now = new Date("2026-09-11T01:00:00.000Z").getTime();
    vi.spyOn(Date, "now").mockReturnValue(now);
    const { config, stats } = memoryKv();
    await config.put(externalCacheKey(source.id), "vless://old@example.com:443", {
      metadata: { fetchedAt: Math.floor(now / 1_000) - 600 },
    });
    stats.puts = 0;
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValue(new Response("vless://fresh@example.com:443"));
    vi.stubGlobal("fetch", fetcher);

    await expect(readSubscriptionContent(source, config, 1_000)).resolves.toContain("fresh");
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(stats.puts).toBe(0);
  });
});
