import { describe, expect, it } from "vitest";

import type { Env } from "../../src/app/env";
import { createApp } from "../../src/app/router";

function testEnv(runtimeConfig: unknown = null): Env {
  return {
    CONFIG: {
      get: async () => runtimeConfig,
    } as unknown as KVNamespace,
  } as Env;
}

describe("baseline HTTP routes", () => {
  it("reports health and assigns a request id", async () => {
    const response = await createApp().request("/health", {}, testEnv());

    expect(response.status).toBe(200);
    expect(response.headers.get("X-Request-ID")).toBeTruthy();
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(await response.json()).toEqual({ ok: true, data: { status: "ok" } });
  });

  it("returns public runtime config without internal fetch settings", async () => {
    const response = await createApp().request("/api/v2/bootstrap", {}, testEnv({
      notice: {
        enabled: true,
        id: "maintenance",
        title: "维护公告",
        content: "稍后恢复",
        showOnce: false,
      },
      appUpdate: {
        versionCode: 21,
        versionName: "2.1.0",
        downloadUrl: "https://example.com/mobile.apk",
        force: false,
        changelog: "移动端更新",
      },
      pcAppUpdate: {
        versionCode: 34,
        versionName: "3.4.0",
        downloadUrl: "https://example.com/setup.exe",
        force: true,
        changelog: "PC 端更新",
      },
      settings: {
        websiteUrl: "https://example.com",
        subscriptionFetchTimeoutMs: 9_000,
      },
    }));
    const body = await response.json() as {
      ok: boolean;
      data: Record<string, unknown>;
    };

    expect(response.status).toBe(200);
    expect(body.ok).toBe(true);
    expect(body.data.crypto).toEqual({
      version: 2,
      algorithm: "P256-HKDF-SHA256-A256GCM",
    });
    expect(body.data.notice).toMatchObject({ id: "maintenance", enabled: true });
    expect(body.data.appUpdate).toMatchObject({ versionCode: 21, versionName: "2.1.0" });
    expect(body.data.pcAppUpdate).toMatchObject({ versionCode: 34, versionName: "3.4.0", force: true });
    expect(body.data.settings).toEqual({
      websiteUrl: "https://example.com",
      feedbackEmail: "",
      feedbackUrl: "",
      githubUrl: "",
    });
  });

  it("uses the unified error envelope for unknown routes", async () => {
    const response = await createApp().request("/missing", {}, testEnv());
    const body = await response.json() as Record<string, unknown>;

    expect(response.status).toBe(404);
    expect(body).toMatchObject({ ok: false, error: "not_found" });
    expect(body.requestId).toBe(response.headers.get("X-Request-ID"));
  });
});
