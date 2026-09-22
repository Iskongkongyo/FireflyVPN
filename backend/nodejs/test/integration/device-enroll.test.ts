import { afterEach, beforeEach, describe, expect, it } from "vitest";

import migrationSql from "../../database/migrations/0001_bootstrap.sql?raw";
import auditIpAddressSql from "../../database/migrations/0004_audit_ip_address.sql?raw";
import writeOptimizationSql from "../../database/migrations/0006_d1_write_optimization.sql?raw";
import type { Env } from "../../src/app/env";
import { createApp } from "../../src/app/router";
import { encodeBase64Url } from "../../src/foundation/crypto/base64url";
import { sha256Base64Url } from "../../src/foundation/crypto/digest";
import { AppError } from "../../src/foundation/http/errors";
import { authenticateDevice } from "../../src/modules/secure-delivery/authenticator";
import { enforceRateLimit } from "../../src/modules/secure-delivery/rate-guard";
import { touchDeviceIfStale } from "../../src/modules/device-registry/repository";
import { NodeDatabase } from "../../src/node/database";
import { NodeKvStore } from "../../src/node/kv";

const DEVICE_ID = "a".repeat(64);
const IP_ADDRESS = "203.0.113.10";

interface EnrollResponse {
  ok: boolean;
  data: {
    accountId: string;
    deviceId: string;
    deviceToken?: string;
    cryptoVersion: number;
    alreadyEnrolled: boolean;
  };
}

async function publicKeySpki(): Promise<string> {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDH", namedCurve: "P-256" },
    true,
    ["deriveBits"],
  );
  return encodeBase64Url(await crypto.subtle.exportKey("spki", pair.publicKey));
}

describe("anonymous device enrollment", () => {
  let database: NodeDatabase;
  let env: Env;
  let publicKey: string;

  beforeEach(async () => {
    database = new NodeDatabase(":memory:");
    database.sqlite.exec(`${migrationSql}\n${auditIpAddressSql}\n${writeOptimizationSql}`);
    const db = database.asDatabase();
    env = {
      DB: db,
      CONFIG: new NodeKvStore(database.sqlite).asKeyValueStore(),
      APP_ENV: "test",
      ADMIN_ROUTE: "test-admin",
      ADMIN_TOKEN: "test-admin-token-not-used-here",
    } as Env;
    publicKey = await publicKeySpki();
  });

  afterEach(() => database.close());

  async function enroll(
    overrides: Record<string, unknown> = {},
    token?: string,
  ): Promise<Response> {
    return createApp().request("/api/v2/devices/enroll", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "CF-Connecting-IP": IP_ADDRESS,
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({
        deviceId: DEVICE_ID,
        platform: "android",
        deviceName: "Pixel Test",
        publicKey,
        cryptoVersion: 2,
        ...overrides,
      }),
    }, env);
  }

  it("creates an anonymous account and stores only the token hash", async () => {
    const response = await enroll();
    const body = await response.json() as EnrollResponse;

    expect(response.status).toBe(201);
    expect(body.ok).toBe(true);
    expect(body.data.accountId).toMatch(/^[a-f0-9]{64}$/);
    expect(body.data.deviceId).toBe(DEVICE_ID);
    expect(body.data.deviceToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(body.data.alreadyEnrolled).toBe(false);

    const stored = await env.DB.prepare(`
      SELECT d.token_hash AS tokenHash, d.public_key_spki AS publicKey,
             d.account_id AS accountId
      FROM devices d JOIN accounts a ON a.id = d.account_id
      WHERE d.id = ?1
    `).bind(DEVICE_ID).first<{
      tokenHash: string;
      publicKey: string;
      accountId: string;
    }>();

    expect(stored?.accountId).toBe(body.data.accountId);
    expect(stored?.publicKey).toBe(publicKey);
    expect(stored?.tokenHash).not.toBe(body.data.deviceToken);
    expect(stored?.tokenHash).toBe(await sha256Base64Url(body.data.deviceToken!));
  });

  it("requires the existing token and never returns it again", async () => {
    const created = await (await enroll()).json() as EnrollResponse;

    expect((await enroll()).status).toBe(401);
    expect((await enroll({}, "x".repeat(43))).status).toBe(401);

    const repeatedResponse = await enroll({}, created.data.deviceToken);
    const repeated = await repeatedResponse.json() as EnrollResponse;
    expect(repeatedResponse.status).toBe(200);
    expect(repeated.data.alreadyEnrolled).toBe(true);
    expect(repeated.data).not.toHaveProperty("deviceToken");
    expect(repeated.data.accountId).toBe(created.data.accountId);
  });

  it("rebinds a fresh key and token after reinstalling the same active device", async () => {
    const created = await (await enroll()).json() as EnrollResponse;
    const reinstalledKey = await publicKeySpki();
    const response = await enroll({ publicKey: reinstalledKey });
    const rebound = await response.json() as EnrollResponse;

    expect(response.status).toBe(200);
    expect(rebound.data).toMatchObject({
      accountId: created.data.accountId,
      deviceId: DEVICE_ID,
      cryptoVersion: 2,
      alreadyEnrolled: true,
    });
    expect(rebound.data.deviceToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
    const row = await env.DB.prepare(
      "SELECT public_key_spki AS publicKey, token_hash AS tokenHash FROM devices WHERE id = ?1",
    ).bind(DEVICE_ID).first<{ publicKey: string; tokenHash: string }>();
    expect(row?.publicKey).toBe(reinstalledKey);
    expect(row?.tokenHash).toBe(await sha256Base64Url(rebound.data.deviceToken!));

    const oldCredential = new Request("https://example.test/protected", {
      headers: {
        "X-Firefly-Device-ID": DEVICE_ID,
        Authorization: `Bearer ${created.data.deviceToken}`,
      },
    });
    await expect(authenticateDevice(oldCredential, env)).rejects.toMatchObject({
      code: "unauthorized",
    });
  });

  it("keeps a reinstalled device banned until an administrator unbans it", async () => {
    const created = await (await enroll()).json() as EnrollResponse;
    await env.DB.prepare("UPDATE devices SET status = 'banned' WHERE id = ?1")
      .bind(DEVICE_ID).run();
    const reinstalledKey = await publicKeySpki();

    const banned = await enroll({ publicKey: reinstalledKey });

    expect(banned.status).toBe(403);
    expect(await banned.json()).toMatchObject({ ok: false, error: "device_banned" });
    const stillBound = await env.DB.prepare(
      "SELECT public_key_spki AS publicKey FROM devices WHERE id = ?1",
    ).bind(DEVICE_ID).first<{ publicKey: string }>();
    expect(stillBound?.publicKey).toBe(publicKey);

    await env.DB.prepare("UPDATE devices SET status = 'active' WHERE id = ?1")
      .bind(DEVICE_ID).run();
    const unbanned = await enroll({ publicKey: reinstalledKey });
    const rebound = await unbanned.json() as EnrollResponse;
    expect(unbanned.status).toBe(200);
    expect(rebound.data.accountId).toBe(created.data.accountId);
    expect(rebound.data.deviceToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
  });

  it("rejects invalid IDs, platforms, public keys, and oversized JSON", async () => {
    expect((await enroll({ deviceId: "short" })).status).toBe(400);
    expect((await enroll({ platform: "linux" })).status).toBe(400);
    expect((await enroll({ publicKey: "not-a-key" })).status).toBe(400);

    const oversized = await createApp().request("/api/v2/devices/enroll", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ padding: "x".repeat(65_536) }),
    }, env);
    expect(oversized.status).toBe(413);
  });

  it("authenticates the issued token and rejects a different token", async () => {
    const created = await (await enroll()).json() as EnrollResponse;
    const authenticatedRequest = new Request("https://example.test/protected", {
      headers: {
        "X-Firefly-Device-ID": DEVICE_ID,
        Authorization: `Bearer ${created.data.deviceToken}`,
      },
    });

    await expect(authenticateDevice(authenticatedRequest, env)).resolves.toMatchObject({
      accountId: created.data.accountId,
      deviceId: DEVICE_ID,
      platform: "android",
      publicKeySpki: publicKey,
    });

    const wrongTokenRequest = new Request("https://example.test/protected", {
      headers: {
        "X-Firefly-Device-ID": DEVICE_ID,
        Authorization: `Bearer ${"z".repeat(43)}`,
      },
    });
    await expect(authenticateDevice(wrongTokenRequest, env)).rejects.toMatchObject({
      code: "unauthorized",
      status: 401,
    });

    const otherDeviceId = "d".repeat(64);
    const otherKey = await publicKeySpki();
    const other = await (await enroll({ deviceId: otherDeviceId, publicKey: otherKey })).json() as EnrollResponse;
    const crossedIdentity = new Request("https://example.test/protected", {
      headers: {
        "X-Firefly-Device-ID": otherDeviceId,
        Authorization: `Bearer ${created.data.deviceToken}`,
      },
    });
    await expect(authenticateDevice(crossedIdentity, env)).rejects.toMatchObject({
      code: "unauthorized",
      status: 401,
    });
    const reverseCrossedIdentity = new Request("https://example.test/protected", {
      headers: {
        "X-Firefly-Device-ID": DEVICE_ID,
        Authorization: `Bearer ${other.data.deviceToken}`,
      },
    });
    await expect(authenticateDevice(reverseCrossedIdentity, env)).rejects.toMatchObject({
      code: "unauthorized",
      status: 401,
    });
  });

  it("updates device activity at most once per fifteen minutes", async () => {
    await enroll();
    const staleAt = "2026-09-06T11:00:00.000Z";
    const firstTouchAt = new Date("2026-09-06T12:00:00.000Z");
    await env.DB.prepare("UPDATE devices SET last_seen_at = ?2, updated_at = ?2 WHERE id = ?1")
      .bind(DEVICE_ID, staleAt)
      .run();

    await expect(touchDeviceIfStale(env.DB, DEVICE_ID, staleAt, firstTouchAt)).resolves.toBe(true);
    await expect(touchDeviceIfStale(
      env.DB,
      DEVICE_ID,
      firstTouchAt.toISOString(),
      new Date("2026-09-06T12:14:59.999Z"),
    )).resolves.toBe(false);
    expect((await env.DB.prepare("SELECT last_seen_at AS lastSeenAt FROM devices WHERE id = ?1")
      .bind(DEVICE_ID)
      .first<{ lastSeenAt: string }>())?.lastSeenAt).toBe(firstTouchAt.toISOString());

    const nextTouchAt = new Date("2026-09-06T12:15:00.000Z");
    await expect(touchDeviceIfStale(
      env.DB,
      DEVICE_ID,
      firstTouchAt.toISOString(),
      nextTouchAt,
    )).resolves.toBe(true);
    expect((await env.DB.prepare("SELECT last_seen_at AS lastSeenAt FROM devices WHERE id = ?1")
      .bind(DEVICE_ID)
      .first<{ lastSeenAt: string }>())?.lastSeenAt).toBe(nextTouchAt.toISOString());
  });

  it("enforces the documented device enrollment boundary", async () => {
    const now = Date.UTC(2026, 8, 6, 0, 0, 0);
    for (let request = 0; request < 6; request += 1) {
      await expect(enforceRateLimit(env.DB, "device_enroll", {
        deviceId: DEVICE_ID,
      }, now)).resolves.toBeUndefined();
    }
    await expect(enforceRateLimit(env.DB, "device_enroll", {
      deviceId: DEVICE_ID,
    }, now)).rejects.toEqual(expect.objectContaining<Partial<AppError>>({
      code: "rate_limited",
      status: 429,
    }));
  });

  it("lists only safe device fields and rotates the key and token atomically", async () => {
    const created = await (await enroll()).json() as EnrollResponse;
    const token = created.data.deviceToken!;
    const authHeaders = {
      Authorization: `Bearer ${token}`,
      "X-Firefly-Device-ID": DEVICE_ID,
    };
    const listed = await createApp().request("/api/v2/devices", {
      headers: authHeaders,
    }, env);
    const listedBody = await listed.json() as { data: Array<Record<string, unknown>> };
    expect(listed.status).toBe(200);
    expect(listedBody.data[0]).toMatchObject({ id: DEVICE_ID, status: "active" });
    expect(listedBody.data[0]).toHaveProperty("publicKeyFingerprint");
    expect(listedBody.data[0]).not.toHaveProperty("publicKeySpki");
    expect(listedBody.data[0]).not.toHaveProperty("tokenHash");

    const nextPublicKey = await publicKeySpki();
    const rotated = await createApp().request(`/api/v2/devices/${DEVICE_ID}/rotate-key`, {
      method: "POST",
      headers: { ...authHeaders, "Content-Type": "application/json" },
      body: JSON.stringify({ publicKey: nextPublicKey }),
    }, env);
    const rotatedBody = await rotated.json() as { data: { deviceToken: string } };
    expect(rotated.status).toBe(200);
    expect(rotatedBody.data.deviceToken).toMatch(/^[A-Za-z0-9_-]{43}$/u);
    expect(rotatedBody.data.deviceToken).not.toBe(token);

    await expect(authenticateDevice(new Request("https://example.test/protected", {
      headers: authHeaders,
    }), env)).rejects.toMatchObject({ code: "unauthorized" });
    await expect(authenticateDevice(new Request("https://example.test/protected", {
      headers: {
        Authorization: `Bearer ${rotatedBody.data.deviceToken}`,
        "X-Firefly-Device-ID": DEVICE_ID,
      },
    }), env)).resolves.toMatchObject({ publicKeySpki: nextPublicKey });
  });

  it("revokes another device only when it belongs to the authenticated anonymous account", async () => {
    const created = await (await enroll()).json() as EnrollResponse;
    const secondDeviceId = "b".repeat(64);
    const secondToken = encodeBase64Url(crypto.getRandomValues(new Uint8Array(32)));
    const now = new Date().toISOString();
    await env.DB.prepare(`
      INSERT INTO devices (
        id, account_id, platform, display_name, status, public_key_spki,
        crypto_version, token_hash, token_issued_at, created_at, updated_at, last_seen_at
      ) VALUES (?1, ?2, 'windows', 'Second', 'active', ?3, 2, ?4, ?5, ?5, ?5, ?5)
    `).bind(
      secondDeviceId,
      created.data.accountId,
      await publicKeySpki(),
      await sha256Base64Url(secondToken),
      now,
    ).run();

    const revoked = await createApp().request(`/api/v2/devices/${secondDeviceId}/revoke`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${created.data.deviceToken}`,
        "X-Firefly-Device-ID": DEVICE_ID,
      },
    }, env);
    expect(revoked.status).toBe(200);
    expect(await revoked.json()).toMatchObject({
      ok: true,
      data: { deviceId: secondDeviceId, status: "revoked" },
    });

    await expect(authenticateDevice(new Request("https://example.test/protected", {
      headers: {
        Authorization: `Bearer ${secondToken}`,
        "X-Firefly-Device-ID": secondDeviceId,
      },
    }), env)).rejects.toMatchObject({ code: "device_revoked" });
    const selfRevoke = await createApp().request(`/api/v2/devices/${DEVICE_ID}/revoke`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${created.data.deviceToken}`,
        "X-Firefly-Device-ID": DEVICE_ID,
      },
    }, env);
    expect(selfRevoke.status).toBe(400);
  });

  it("returns a safe anonymous account view and invalidates every device on deletion", async () => {
    const created = await (await enroll()).json() as EnrollResponse;
    const headers = {
      Authorization: `Bearer ${created.data.deviceToken}`,
      "X-Firefly-Device-ID": DEVICE_ID,
    };
    const account = await createApp().request("/api/v2/accounts/me", { headers }, env);
    const accountBody = await account.json() as { data: Record<string, unknown> };
    expect(account.status).toBe(200);
    expect(accountBody.data).toMatchObject({
      id: created.data.accountId,
      status: "active",
    });
    expect(accountBody.data).not.toHaveProperty("email");

    const deleted = await createApp().request("/api/v2/accounts/me", {
      method: "DELETE",
      headers,
    }, env);
    expect(deleted.status).toBe(200);
    expect(await deleted.json()).toMatchObject({
      ok: true,
      data: { accountId: created.data.accountId, status: "deleted", revokedDevices: 1 },
    });
    await expect(authenticateDevice(new Request("https://example.test/protected", { headers }), env))
      .rejects.toMatchObject({ code: "account_deleted", status: 403 });
    const stored = await env.DB.prepare(`
      SELECT a.status AS accountStatus, d.status AS deviceStatus
      FROM accounts a JOIN devices d ON d.account_id = a.id WHERE a.id = ?1
    `).bind(created.data.accountId).first<{ accountStatus: string; deviceStatus: string }>();
    expect(stored).toEqual({ accountStatus: "deleted", deviceStatus: "revoked" });
  });
});
