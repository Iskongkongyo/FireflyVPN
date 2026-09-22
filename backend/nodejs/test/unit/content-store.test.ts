import { afterEach, describe, expect, it, vi } from "vitest";

import {
  deleteManagedSubscriptionContent,
  externalCacheKey,
  managedContentKey,
  readSubscriptionContent,
  writeManagedSubscriptionContent,
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
  mergeMode: "external_first",
  externalHealthEnabled: 1,
  externalHealthStatus: "unknown",
  externalLastCheckedAt: null,
  externalLastSuccessAt: null,
  externalLastError: null,
  externalFallbackActive: 0,
  createdAt: "2026-09-06T00:00:00.000Z",
  updatedAt: "2026-09-06T00:00:00.000Z",
};

function memoryKv(): {
  config: KeyValueStore;
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
  } as unknown as KeyValueStore;
  return { config, stats };
}

describe("external subscription cache", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("combines optional managed nodes without caching the combined plaintext", async () => {
    const { config } = memoryKv();
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      new Response("vless://external@example.com:443"),
    );
    vi.stubGlobal("fetch", fetcher);

    await expect(readSubscriptionContent(source, config, 1_000))
      .resolves.toBe("vless://external@example.com:443");
    await writeManagedSubscriptionContent(config, source.id, "ss://managed@example.com:443");
    await expect(readSubscriptionContent(source, config, 1_000))
      .resolves.toBe("vless://external@example.com:443\nss://managed@example.com:443");
    await deleteManagedSubscriptionContent(config, source.id);
    expect(await config.get(managedContentKey(source.id))).toBeNull();
    await expect(readSubscriptionContent(source, config, 1_000))
      .resolves.toBe("vless://external@example.com:443");
    expect(fetcher).toHaveBeenCalledOnce();
  });

  it("reads a managed-only source without fetching an external URL", async () => {
    const { config } = memoryKv();
    await config.put(managedContentKey(source.id), "vless://legacy@example.com:443");
    await expect(readSubscriptionContent({ ...source, sourceType: "managed", sourceUrl: null }, config, 0))
      .resolves.toBe("vless://legacy@example.com:443");
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

  it("persists refreshed content after the configured TTL expires", async () => {
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
    expect(stats.puts).toBe(1);
  });

  it("falls back to the last successful external content and reports degraded health", async () => {
    let now = new Date("2026-09-11T01:00:00.000Z").getTime();
    vi.spyOn(Date, "now").mockImplementation(() => now);
    const { config } = memoryKv();
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response("vless://healthy@example.com:443"))
      .mockRejectedValue(new TypeError("origin unavailable"));
    vi.stubGlobal("fetch", fetcher);
    const reporter = vi.fn(async () => undefined);
    const shortCacheSource = { ...source, cacheTtlSeconds: 30 };

    await expect(readSubscriptionContent(shortCacheSource, config, 1_000, false, reporter))
      .resolves.toContain("healthy");
    now += 31_000;
    await expect(readSubscriptionContent(shortCacheSource, config, 1_000, false, reporter))
      .resolves.toContain("healthy");

    expect(fetcher).toHaveBeenCalledTimes(4);
    expect(reporter).toHaveBeenLastCalledWith(expect.objectContaining({
      healthy: false,
      fallbackActive: true,
      errorCode: "upstream_failed",
    }));
  });

  it("does not use stale external content when health fallback is disabled", async () => {
    let now = new Date("2026-09-11T01:00:00.000Z").getTime();
    vi.spyOn(Date, "now").mockImplementation(() => now);
    const { config } = memoryKv();
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response("vless://healthy@example.com:443"))
      .mockRejectedValue(new TypeError("origin unavailable"));
    vi.stubGlobal("fetch", fetcher);
    const disabledSource = {
      ...source,
      cacheTtlSeconds: 30,
      externalHealthEnabled: 0 as const,
      externalHealthStatus: "disabled" as const,
    };

    await expect(readSubscriptionContent(disabledSource, config, 1_000))
      .resolves.toContain("healthy");
    now += 31_000;
    await expect(readSubscriptionContent(disabledSource, config, 1_000))
      .rejects.toMatchObject({ code: "upstream_failed" });
  });

  it("keeps forced refresh isolated from a concurrent fallback-enabled read", async () => {
    let now = new Date("2026-09-11T01:00:00.000Z").getTime();
    vi.spyOn(Date, "now").mockImplementation(() => now);
    const { config } = memoryKv();
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response("vless://healthy@example.com:443"))
      .mockRejectedValue(new TypeError("origin unavailable"));
    vi.stubGlobal("fetch", fetcher);
    const shortCacheSource = { ...source, cacheTtlSeconds: 30 };

    await expect(readSubscriptionContent(shortCacheSource, config, 1_000))
      .resolves.toContain("healthy");
    now += 31_000;

    const forcedReporter = vi.fn(async () => undefined);
    const normalRead = readSubscriptionContent(shortCacheSource, config, 1_000);
    const forcedRead = readSubscriptionContent(shortCacheSource, config, 1_000, true, forcedReporter);
    const forcedAssertion = expect(forcedRead).rejects.toMatchObject({ code: "upstream_failed" });
    await expect(normalRead).resolves.toContain("healthy");
    await forcedAssertion;
    expect(fetcher).toHaveBeenCalledTimes(7);
    expect(forcedReporter).toHaveBeenLastCalledWith(expect.objectContaining({
      healthy: false,
      fallbackActive: true,
    }));
  });
});
