import { afterAll, beforeAll, describe, expect, it } from "vitest";

import migrationSql from "../../database/migrations/0001_bootstrap.sql?raw";
import writeOptimizationSql from "../../database/migrations/0006_d1_write_optimization.sql?raw";
import type { Env } from "../../src/app/env";
import { createApp } from "../../src/app/router";
import { encodeBase64Url } from "../../src/foundation/crypto/base64url";
import { NodeDatabase } from "../../src/node/database";
import { NodeKvStore } from "../../src/node/kv";

const DEVICE_ID = "c".repeat(64);

describe("usage reporting", () => {
  let database: NodeDatabase;
  let env: Env;
  let deviceToken: string;
  let accountId: string;

  beforeAll(async () => {
    database = new NodeDatabase(":memory:");
    database.sqlite.exec(`${migrationSql}\n${writeOptimizationSql}`);
    const db = database.asDatabase();
    env = {
      DB: db,
      CONFIG: new NodeKvStore(database.sqlite).asKeyValueStore(),
      APP_ENV: "test",
      ADMIN_ROUTE: "admin-test",
      ADMIN_TOKEN: "admin-token-long-enough-for-tests",
    } as Env;

    const pair = await crypto.subtle.generateKey(
      { name: "ECDH", namedCurve: "P-256" },
      true,
      ["deriveBits"],
    );
    const publicKey = encodeBase64Url(await crypto.subtle.exportKey("spki", pair.publicKey));
    const response = await createApp().request("/api/v2/devices/enroll", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceId: DEVICE_ID, platform: "android", publicKey }),
    }, env);
    const enrollment = await response.json() as {
      data: { deviceToken: string; accountId: string };
    };
    deviceToken = enrollment.data.deviceToken;
    accountId = enrollment.data.accountId;
  });

  afterAll(() => database.close());

  async function report(body: Record<string, unknown>): Promise<Response> {
    return createApp().request("/api/v2/usage/report", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${deviceToken}`,
        "X-Firefly-Device-ID": DEVICE_ID,
      },
      body: JSON.stringify(body),
    }, env);
  }

  it("counts each session once and ignores a client-supplied account id", async () => {
    const first = await report({
      sessionId: "session-1",
      uploadBytes: 100,
      downloadBytes: 200,
      accountId: "attacker-selected-account",
    });
    expect(await first.json()).toMatchObject({
      ok: true,
      data: { duplicate: false, sessionId: "session-1" },
    });

    const duplicate = await report({
      sessionId: "session-1",
      uploadBytes: 100,
      downloadBytes: 200,
    });
    expect(await duplicate.json()).toMatchObject({
      ok: true,
      data: { duplicate: true, sessionId: "session-1" },
    });

    await report({ sessionId: "session-2", uploadBytes: 7, downloadBytes: 9 });
    const daily = await env.DB.prepare(`
      SELECT account_id AS accountId, upload_bytes AS uploadBytes,
             download_bytes AS downloadBytes
      FROM usage_daily
    `).first<{ accountId: string; uploadBytes: number; downloadBytes: number }>();
    const monthly = await env.DB.prepare(`
      SELECT upload_bytes AS uploadBytes, download_bytes AS downloadBytes
      FROM usage_monthly WHERE account_id = ?1
    `).bind(accountId).first<{ uploadBytes: number; downloadBytes: number }>();

    expect(daily).toMatchObject({ accountId, uploadBytes: 107, downloadBytes: 209 });
    expect(monthly).toMatchObject({ uploadBytes: 107, downloadBytes: 209 });
    expect((await report({ sessionId: "bad", uploadBytes: -1, downloadBytes: 0 })).status)
      .toBe(400);
  });
});
