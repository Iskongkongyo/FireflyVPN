import { convertV4MiniflareOptions, Miniflare } from "miniflare";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

import migrationSql from "../../database/migrations/0001_bootstrap.sql?raw";
import subscriptionNotesSql from "../../database/migrations/0003_subscription_notes.sql?raw";
import auditIpAddressSql from "../../database/migrations/0004_audit_ip_address.sql?raw";
import adminJwtSql from "../../database/migrations/0005_admin_jwt.sql?raw";
import writeOptimizationSql from "../../database/migrations/0006_d1_write_optimization.sql?raw";
import type { Env } from "../../src/app/env";
import { createApp } from "../../src/app/router";
import { encodeBase64Url } from "../../src/foundation/crypto/base64url";
import { decryptSubscription } from "../../src/modules/secure-delivery/encryptor";
import type { CryptoV2Envelope } from "../../src/modules/secure-delivery/protocol";

const ADMIN_ROUTE = "private-test-console";
const INITIAL_ADMIN_TOKEN = "admin-token-with-more-than-sixteen-characters";
let adminCredential = INITIAL_ADMIN_TOKEN;
const DEVICE_ID = "b".repeat(64);
const MANAGED_CONTENT = "vless://fixture@example.com:443#Firefly\nss://dGVzdA==#Backup";

describe("secure subscription delivery", () => {
  let miniflare: Miniflare;
  let env: Env;
  let publicKey: string;
  let privateKey: string;
  let deviceToken: string;

  beforeAll(async () => {
    miniflare = new Miniflare(convertV4MiniflareOptions({
      compatibilityDate: "2026-09-06",
      compatibilityFlags: ["nodejs_compat"],
      modules: true,
      script: "export default { fetch() { return new Response('ok') } }",
      d1Databases: ["DB"],
      kvNamespaces: ["CONFIG"],
    }));
    const db = await miniflare.getD1Database("DB");
    const statements = `${migrationSql}\n${subscriptionNotesSql}\n${auditIpAddressSql}\n${adminJwtSql}\n${writeOptimizationSql}`
      .replace(/^PRAGMA foreign_keys = ON;\s*/u, "")
      .split(";")
      .map((statement) => statement.trim())
      .filter(Boolean);
    for (const statement of statements) await db.prepare(statement).run();
    env = {
      DB: db as unknown as D1Database,
      CONFIG: await miniflare.getKVNamespace("CONFIG") as unknown as KVNamespace,
      ASSETS: {
        fetch: async (request: Request) => new Response(
          new URL(request.url).pathname === "/console/index.html" ? "<html>console</html>" : "asset",
          { headers: { "Content-Type": "text/html" } },
        ),
      } as unknown as Fetcher,
      APP_ENV: "test",
      ADMIN_ROUTE,
      ADMIN_TOKEN: INITIAL_ADMIN_TOKEN,
    } as Env;

    const pair = await crypto.subtle.generateKey(
      { name: "ECDH", namedCurve: "P-256" },
      true,
      ["deriveBits"],
    );
    publicKey = encodeBase64Url(await crypto.subtle.exportKey("spki", pair.publicKey));
    privateKey = encodeBase64Url(await crypto.subtle.exportKey("pkcs8", pair.privateKey));

    const enrolled = await createApp().request("/api/v2/devices/enroll", {
      method: "POST",
      headers: { "Content-Type": "application/json", "CF-Connecting-IP": "203.0.113.20" },
      body: JSON.stringify({
        deviceId: DEVICE_ID,
        platform: "android",
        publicKey,
        cryptoVersion: 2,
      }),
    }, env);
    const enrollment = await enrolled.json() as {
      data: { deviceToken: string };
    };
    deviceToken = enrollment.data.deviceToken;
  });

  afterAll(async () => {
    await miniflare.dispose();
  });

  function deviceHeaders(extra: Record<string, string> = {}): Record<string, string> {
    return {
      Authorization: `Bearer ${deviceToken}`,
      "X-Firefly-Device-ID": DEVICE_ID,
      ...extra,
    };
  }

  it("protects admin APIs, stores managed content, encrypts it, and blocks replay", async () => {
    const redirected = await createApp().request(`/${ADMIN_ROUTE}`, {}, env);
    expect(redirected.status).toBe(308);
    expect(redirected.headers.get("Location")).toBe(`/${ADMIN_ROUTE}/`);
    const consolePage = await createApp().request(`/${ADMIN_ROUTE}/`, {}, env);
    expect(consolePage.status).toBe(200);
    expect(await consolePage.text()).toContain("console");

    const denied = await createApp().request(`/${ADMIN_ROUTE}/api/subscriptions`, {}, env);
    expect(denied.status).toBe(401);

    const adminLogin = await createApp().request(`/${ADMIN_ROUTE}/api/session/login`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${adminCredential}`,
        "CF-Connecting-IP": "198.51.100.23",
      },
    }, env);
    expect(adminLogin.status).toBe(200);
    const loginRequestId = adminLogin.headers.get("X-Request-ID")!;
    const adminLoginBody = await adminLogin.json() as { data: { token: string; expiresAt: string } };
    expect(adminLoginBody.data.token.split(".")).toHaveLength(3);
    adminCredential = adminLoginBody.data.token;

    const dashboard = await createApp().request(`/${ADMIN_ROUTE}/api/dashboard`, {
      headers: { Authorization: `Bearer ${adminCredential}` },
    }, env);
    const dashboardBody = await dashboard.json() as {
      data: { daily: Array<{ day: string; uploadBytes: number; downloadBytes: number }> };
    };
    expect(dashboardBody).toMatchObject({
      ok: true,
      data: { accounts: { total: 1 }, devices: { total: 1, active: 1 } },
    });
    expect(dashboardBody.data.daily).toHaveLength(30);
    expect(dashboardBody.data.daily.every((row) => (
      /^\d{4}-\d{2}-\d{2}$/u.test(row.day)
      && Number.isFinite(row.uploadBytes)
      && Number.isFinite(row.downloadBytes)
    ))).toBe(true);

    const accounts = await createApp().request(`/${ADMIN_ROUTE}/api/accounts`, {
      headers: { Authorization: `Bearer ${adminCredential}` },
    }, env);
    const accountsBody = await accounts.json() as {
      data: { items: Array<Record<string, unknown>>; page: number; pageSize: number; total: number; totalPages: number };
    };
    expect(accountsBody.data).toMatchObject({ page: 1, pageSize: 20, total: 1, totalPages: 1 });
    expect(accountsBody.data.items).toHaveLength(1);
    expect(accountsBody.data.items[0]).toMatchObject({ status: "active", deviceCount: 1 });
    expect(accountsBody.data.items[0]).not.toHaveProperty("email");
    expect(accountsBody.data.items[0]).not.toHaveProperty("passwordHash");
    const accountId = String(accountsBody.data.items[0]?.id);
    const accountSearch = await createApp().request(
      `/${ADMIN_ROUTE}/api/accounts?page=9&pageSize=5&q=${accountId}`,
      { headers: { Authorization: `Bearer ${adminCredential}` } },
      env,
    );
    expect(await accountSearch.json()).toMatchObject({
      ok: true,
      data: { page: 1, pageSize: 5, total: 1, totalPages: 1, items: [{ id: accountId }] },
    });

    const accountDetail = await createApp().request(`/${ADMIN_ROUTE}/api/accounts/${accountId}`, {
      headers: { Authorization: `Bearer ${adminCredential}` },
    }, env);
    const accountDetailBody = await accountDetail.json() as {
      data: { devices: Array<Record<string, unknown>> };
    };
    expect(accountDetailBody.data.devices[0]).toHaveProperty("publicKeyFingerprint");
    expect(accountDetailBody.data.devices[0]).not.toHaveProperty("publicKeySpki");

    const accountBanned = await createApp().request(`/${ADMIN_ROUTE}/api/accounts/${accountId}/ban`, {
      method: "POST",
      headers: { Authorization: `Bearer ${adminCredential}` },
    }, env);
    expect(accountBanned.status).toBe(200);
    const deniedByAccount = await createApp().request("/api/v2/subscriptions", {
      headers: deviceHeaders(),
    }, env);
    expect(deniedByAccount.status).toBe(403);
    expect(await deniedByAccount.json()).toMatchObject({ error: "account_banned" });
    const accountUnbanned = await createApp().request(`/${ADMIN_ROUTE}/api/accounts/${accountId}/unban`, {
      method: "POST",
      headers: { Authorization: `Bearer ${adminCredential}` },
    }, env);
    expect(accountUnbanned.status).toBe(200);

    const analytics = await createApp().request(`/${ADMIN_ROUTE}/api/analytics?days=30&months=12`, {
      headers: { Authorization: `Bearer ${adminCredential}` },
    }, env);
    const analyticsBody = await analytics.json() as {
      data: {
        daily: Array<{ day: string; newDevices: number }>;
        monthly: unknown[];
        platforms: unknown[];
        cumulative: Record<string, number>;
      };
    };
    expect(analyticsBody.data.daily).toHaveLength(30);
    expect(analyticsBody.data.daily.at(-1)).toMatchObject({ newDevices: 1 });
    expect(analyticsBody.data.monthly).toHaveLength(12);
    expect(analyticsBody.data.platforms).toEqual([{ platform: "android", count: 1 }]);
    expect(analyticsBody.data.cumulative).toMatchObject({ uploadBytes: 0, downloadBytes: 0, totalBytes: 0 });

    const devices = await createApp().request(`/${ADMIN_ROUTE}/api/devices`, {
      headers: { Authorization: `Bearer ${adminCredential}` },
    }, env);
    const deviceBody = await devices.json() as {
      data: { items: Array<Record<string, unknown>>; page: number; pageSize: number; total: number; totalPages: number };
    };
    expect(deviceBody.data).toMatchObject({ page: 1, pageSize: 20, total: 1, totalPages: 1 });
    expect(deviceBody.data.items[0]).toMatchObject({ id: DEVICE_ID, status: "active" });
    expect(deviceBody.data.items[0]).toHaveProperty("publicKeyFingerprint");
    expect(deviceBody.data.items[0]).not.toHaveProperty("publicKeySpki");
    expect(deviceBody.data.items[0]).not.toHaveProperty("tokenHash");
    const deviceSearch = await createApp().request(
      `/${ADMIN_ROUTE}/api/devices?pageSize=5&q=android`,
      { headers: { Authorization: `Bearer ${adminCredential}` } },
      env,
    );
    expect(await deviceSearch.json()).toMatchObject({
      ok: true,
      data: { page: 1, pageSize: 5, total: 1, items: [{ id: DEVICE_ID, platform: "android" }] },
    });
    for (const query of [DEVICE_ID, accountId]) {
      const exactDeviceSearch = await createApp().request(
        `/${ADMIN_ROUTE}/api/devices?pageSize=5&q=${query}`,
      { headers: { Authorization: `Bearer ${adminCredential}` } },
        env,
      );
      expect(await exactDeviceSearch.json()).toMatchObject({
        ok: true,
        data: { total: 1, items: [{ id: DEVICE_ID, accountId }] },
      });
    }

    const settings = await createApp().request(`/${ADMIN_ROUTE}/api/settings`, {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${adminCredential}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        notice: { enabled: true, id: "hello", title: "公告", content: "欢迎", showOnce: true },
        appUpdate: null,
        pcAppUpdate: {
          versionCode: 7,
          versionName: "0.7.0",
          downloadUrl: "https://example.com/firefly-setup.exe",
          force: false,
          changelog: "PC 更新",
        },
        settings: { websiteUrl: "https://example.com", subscriptionFetchTimeoutMs: 12_000 },
      }),
    }, env);
    expect(settings.status).toBe(200);
    const bootstrap = await createApp().request("/api/v2/bootstrap", {}, env);
    expect(await bootstrap.json()).toMatchObject({
      ok: true,
      data: {
        notice: { id: "hello" },
        pcAppUpdate: { versionCode: 7, versionName: "0.7.0", force: false },
        settings: { websiteUrl: "https://example.com" },
      },
    });

    const created = await createApp().request(`/${ADMIN_ROUTE}/api/subscriptions`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${adminCredential}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        id: "main",
        name: "主线路",
        note: "主要托管线路",
        sourceType: "managed",
        managedContent: MANAGED_CONTENT,
        enabled: true,
        sortOrder: 0,
      }),
    }, env);
    expect(created.status).toBe(201);
    const createdText = await created.text();
    expect(createdText).toContain("主要托管线路");
    expect(createdText).not.toContain(MANAGED_CONTENT);

    const deniedDetail = await createApp().request(
      `/${ADMIN_ROUTE}/api/subscriptions/main`,
      {},
      env,
    );
    expect(deniedDetail.status).toBe(401);

    const editableDetail = await createApp().request(
      `/${ADMIN_ROUTE}/api/subscriptions/main`,
      { headers: { Authorization: `Bearer ${adminCredential}` } },
      env,
    );
    expect(editableDetail.status).toBe(200);
    expect(editableDetail.headers.get("Cache-Control")).toBe("no-store");
    expect(await editableDetail.json()).toMatchObject({
      ok: true,
      data: {
        id: "main",
        sourceType: "managed",
        managedContent: MANAGED_CONTENT,
      },
    });

    const audit = await env.DB.prepare(`
      SELECT action, detail_json AS detail FROM admin_audit_logs
      WHERE target_id = 'main'
    `).first<{ action: string; detail: string }>();
    expect(audit?.action).toBe("subscription.create");
    expect(audit?.detail).not.toContain(MANAGED_CONTENT);

    const catalogResponse = await createApp().request("/api/v2/subscriptions", {
      headers: deviceHeaders(),
    }, env);
    expect(catalogResponse.status).toBe(200);
    expect(await catalogResponse.json()).toMatchObject({
      ok: true,
      data: [{
        id: "main",
        name: "主线路",
        contentUrl: "/api/v2/subscriptions/main/content",
        cryptoVersion: 2,
      }],
    });

    const challenge = encodeBase64Url(crypto.getRandomValues(new Uint8Array(16)));
    const contentHeaders = deviceHeaders({
      "X-Firefly-Crypto-Version": "2",
      "X-Firefly-Challenge": challenge,
    });
    const encrypted = await createApp().request("/api/v2/subscriptions/main/content", {
      headers: contentHeaders,
    }, env);
    expect(encrypted.status).toBe(200);
    expect(encrypted.headers.get("Cache-Control")).toBe("no-store");
    expect(encrypted.headers.get("X-Firefly-Crypto-Version")).toBe("2");
    const envelope = await encrypted.json() as CryptoV2Envelope;
    expect(envelope.requestId).toBe(challenge);
    expect(envelope.deviceId).toBe(DEVICE_ID);
    expect(envelope.subscriptionId).toBe("main");
    await expect(decryptSubscription(envelope, privateKey)).resolves.toBe(MANAGED_CONTENT);

    const banned = await createApp().request(`/${ADMIN_ROUTE}/api/devices/${DEVICE_ID}/ban`, {
      method: "POST",
      headers: { Authorization: `Bearer ${adminCredential}` },
    }, env);
    expect(banned.status).toBe(200);
    const bannedDevice = await createApp().request("/api/v2/subscriptions", {
      headers: deviceHeaders(),
    }, env);
    expect(bannedDevice.status).toBe(403);
    const unbanned = await createApp().request(`/${ADMIN_ROUTE}/api/devices/${DEVICE_ID}/unban`, {
      method: "POST",
      headers: { Authorization: `Bearer ${adminCredential}` },
    }, env);
    expect(unbanned.status).toBe(200);

    const replay = await createApp().request("/api/v2/subscriptions/main/content", {
      headers: contentHeaders,
    }, env);
    expect(replay.status).toBe(409);
    expect(await replay.json()).toMatchObject({ ok: false, error: "replay_detected" });

    const disabled = await createApp().request(`/${ADMIN_ROUTE}/api/subscriptions/main`, {
      method: "PATCH",
      headers: {
        Authorization: `Bearer ${adminCredential}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ enabled: false, name: "暂时停用" }),
    }, env);
    expect(disabled.status).toBe(200);

    const hiddenCatalog = await createApp().request("/api/v2/subscriptions", {
      headers: deviceHeaders(),
    }, env);
    expect(await hiddenCatalog.json()).toMatchObject({ ok: true, data: [] });

    const removed = await createApp().request(`/${ADMIN_ROUTE}/api/subscriptions/main`, {
      method: "DELETE",
      headers: { Authorization: `Bearer ${adminCredential}` },
    }, env);
    expect(removed.status).toBe(200);
    expect(await env.CONFIG.get("subscription:managed:main")).toBeNull();
    const auditCount = await env.DB.prepare(`
      SELECT COUNT(*) AS count FROM admin_audit_logs WHERE target_id = 'main'
    `).first<{ count: number }>();
    expect(auditCount?.count).toBe(3);

    const nextRoute = "next-private-console";
    for (const invalidToken of ["1234567", "12345678901234567"]) {
      const rejectedToken = await createApp().request(`/${ADMIN_ROUTE}/api/settings/admin-access`, {
        method: "PUT",
        headers: {
          Authorization: `Bearer ${adminCredential}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ accessPath: ADMIN_ROUTE, newAdminToken: invalidToken }),
      }, env);
      expect(rejectedToken.status).toBe(400);
      expect(await rejectedToken.json()).toMatchObject({ ok: false, error: "invalid_request" });
    }
    const nextToken = "nxt-1234";
    const accessUpdate = await createApp().request(`/${ADMIN_ROUTE}/api/settings/admin-access`, {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${adminCredential}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ accessPath: nextRoute, newAdminToken: nextToken }),
    }, env);
    expect(accessUpdate.status).toBe(200);
    expect(await accessUpdate.json()).toMatchObject({
      ok: true,
      data: { accessPath: nextRoute, adminTokenConfigured: true, tokenChanged: true },
    });
    const storedAccess = await env.CONFIG.get("admin:access:v1");
    expect(storedAccess).not.toContain(nextToken);
    expect(storedAccess).not.toContain(INITIAL_ADMIN_TOKEN);

    const oldRoute = await createApp().request(`/${ADMIN_ROUTE}/api/dashboard`, {
      headers: { Authorization: `Bearer ${adminCredential}` },
    }, env);
    expect(oldRoute.status).toBe(404);
    const oldToken = await createApp().request(`/${nextRoute}/api/dashboard`, {
      headers: { Authorization: `Bearer ${adminCredential}` },
    }, env);
    expect(oldToken.status).toBe(401);

    const nextLogin = await createApp().request(`/${nextRoute}/api/session/login`, {
      method: "POST",
      headers: { Authorization: `Bearer ${nextToken}` },
    }, env);
    expect(nextLogin.status).toBe(200);
    const nextLoginBody = await nextLogin.json() as { data: { token: string } };
    const nextJwt = nextLoginBody.data.token;
    const newCredentials = await createApp().request(`/${nextRoute}/api/dashboard`, {
      headers: { Authorization: `Bearer ${nextJwt}` },
    }, env);
    expect(newCredentials.status).toBe(200);

    const adminLogout = await createApp().request(`/${nextRoute}/api/session/logout`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${nextJwt}`,
        "CF-Connecting-IP": "198.51.100.24",
      },
    }, env);
    expect(adminLogout.status).toBe(200);
    const logoutRequestId = adminLogout.headers.get("X-Request-ID")!;
    const revokedSession = await createApp().request(`/${nextRoute}/api/dashboard`, {
      headers: { Authorization: `Bearer ${nextJwt}` },
    }, env);
    expect(revokedSession.status).toBe(401);

    const auditLogin = await createApp().request(`/${nextRoute}/api/session/login`, {
      method: "POST",
      headers: { Authorization: `Bearer ${nextToken}` },
    }, env);
    const auditLoginBody = await auditLogin.json() as { data: { token: string } };
    const auditJwt = auditLoginBody.data.token;

    const loginAudit = await createApp().request(
      `/${nextRoute}/api/audit?page=1&pageSize=5&action=admin.login&requestId=${loginRequestId}&from=2020-01-01T00%3A00%3A00.000Z&to=2100-01-01T00%3A00%3A00.000Z`,
      { headers: { Authorization: `Bearer ${auditJwt}` } },
      env,
    );
    expect(loginAudit.status).toBe(200);
    expect(await loginAudit.json()).toMatchObject({
      ok: true,
      data: {
        total: 1,
        items: [{ action: "admin.login", requestId: loginRequestId, ipAddress: "198.51.100.23" }],
      },
    });

    const logoutAudit = await createApp().request(
      `/${nextRoute}/api/audit?page=1&pageSize=5&action=admin.logout&requestId=${logoutRequestId}`,
      { headers: { Authorization: `Bearer ${auditJwt}` } },
      env,
    );
    expect(logoutAudit.status).toBe(200);
    expect(await logoutAudit.json()).toMatchObject({
      ok: true,
      data: {
        total: 1,
        items: [{ action: "admin.logout", requestId: logoutRequestId, ipAddress: "198.51.100.24" }],
      },
    });

    await env.DB.prepare(`
      INSERT INTO admin_audit_logs (action, request_id, ip_hash, detail_json, created_at)
      VALUES ('fixture.old', 'fixture-old', 'fixture', '{}', '2020-06-01T00:00:00.000Z')
    `).run();
    const rangedCleanup = await createApp().request(`/${nextRoute}/api/audit`, {
      method: "DELETE",
      headers: {
        Authorization: `Bearer ${auditJwt}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        from: "2020-01-01T00:00:00.000Z",
        to: "2020-12-31T23:59:59.999Z",
      }),
    }, env);
    expect(rangedCleanup.status).toBe(200);
    expect(await rangedCleanup.json()).toMatchObject({ ok: true, data: { deleted: 1 } });

    const allCleanup = await createApp().request(`/${nextRoute}/api/audit`, {
      method: "DELETE",
      headers: {
        Authorization: `Bearer ${auditJwt}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ all: true }),
    }, env);
    expect(allCleanup.status).toBe(200);
    const remainingAudit = await env.DB.prepare(`
      SELECT action, detail_json AS detail FROM admin_audit_logs ORDER BY id DESC
    `).all<{ action: string; detail: string }>();
    expect(remainingAudit.results).toHaveLength(1);
    expect(remainingAudit.results[0]).toMatchObject({ action: "audit.cleanup" });
    expect(remainingAudit.results[0]?.detail).toContain('"all":true');

    const jwtSecretUpdate = await createApp().request(`/${nextRoute}/api/settings/admin-access`, {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${auditJwt}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        accessPath: nextRoute,
        newJwtSecret: "custom-jwt-private-key-with-32-characters",
      }),
    }, env);
    expect(jwtSecretUpdate.status).toBe(200);
    expect(await jwtSecretUpdate.json()).toMatchObject({
      ok: true,
      data: { jwtSecretChanged: true },
    });
    const invalidatedBySecretRotation = await createApp().request(`/${nextRoute}/api/dashboard`, {
      headers: { Authorization: `Bearer ${auditJwt}` },
    }, env);
    expect(invalidatedBySecretRotation.status).toBe(401);
    const movedConsole = await createApp().request(`/${nextRoute}/`, {}, env);
    expect(movedConsole.status).toBe(200);
    const background = await createApp().request(`/${nextRoute}/assets/background.jpg`, {}, env);
    expect(background.status).toBe(200);
  }, 30_000);
});
