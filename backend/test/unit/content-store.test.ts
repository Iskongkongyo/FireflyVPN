import { afterEach, describe, expect, it, vi } from "vitest";

import { readSubscriptionContent } from "../../src/modules/subscription-catalog/content-store";
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

function memoryKv(): KVNamespace {
  const values = new Map<string, string>();
  return {
    get: async (key: string, type?: string) => {
      const value = values.get(key) ?? null;
      return type === "json" && value !== null ? JSON.parse(value) : value;
    },
    put: async (key: string, value: string) => {
      values.set(key, value);
    },
    delete: async (key: string) => {
      values.delete(key);
    },
  } as unknown as KVNamespace;
}

describe("external subscription cache", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("uses a fresh KV cache and supports forced refresh", async () => {
    const config = memoryKv();
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
  });
});
