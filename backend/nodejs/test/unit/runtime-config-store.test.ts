import { describe, expect, it, vi } from "vitest";

import type { Env } from "../../src/app/env";
import { readRuntimeConfig, writeRuntimeConfig } from "../../src/modules/runtime-config/store";
import { DEFAULT_RUNTIME_CONFIG } from "../../src/modules/runtime-config/schema";

describe("runtime config cache", () => {
  it("reuses a KV read for sixty seconds and refreshes its cache on writes", async () => {
    let stored: unknown = null;
    const get = vi.fn(async () => stored);
    const put = vi.fn(async (_key: string, value: string) => {
      stored = JSON.parse(value);
    });
    const env = {
      CONFIG: { get, put } as unknown as KeyValueStore,
    } as Env;

    const first = await readRuntimeConfig(env, 1_000);
    const second = await readRuntimeConfig(env, 30_000);
    expect(first).toEqual(DEFAULT_RUNTIME_CONFIG);
    expect(second).toEqual(DEFAULT_RUNTIME_CONFIG);
    expect(get).toHaveBeenCalledTimes(1);

    const next = structuredClone(DEFAULT_RUNTIME_CONFIG);
    next.settings.websiteUrl = "https://example.com";
    await writeRuntimeConfig(env, next);
    expect((await readRuntimeConfig(env)).settings.websiteUrl).toBe("https://example.com");
    expect(get).toHaveBeenCalledTimes(1);
    expect(put).toHaveBeenCalledTimes(1);
  });
});
