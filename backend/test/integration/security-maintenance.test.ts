import { convertV4MiniflareOptions, Miniflare } from "miniflare";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import migrationSql from "../../database/migrations/0001_bootstrap.sql?raw";
import writeOptimizationSql from "../../database/migrations/0006_d1_write_optimization.sql?raw";
import { encodeBase64Url } from "../../src/foundation/crypto/base64url";
import { AppError } from "../../src/foundation/http/errors";
import { cleanupExpiredSecurityState } from "../../src/modules/security-maintenance/service";
import { consumeChallenge } from "../../src/modules/secure-delivery/replay-guard";
import { enforceRateLimit } from "../../src/modules/secure-delivery/rate-guard";

describe("security state maintenance", () => {
  let miniflare: Miniflare;
  let db: D1Database;

  beforeEach(async () => {
    miniflare = new Miniflare(convertV4MiniflareOptions({
      compatibilityDate: "2026-09-06",
      compatibilityFlags: ["nodejs_compat"],
      modules: true,
      script: "export default { fetch() { return new Response('ok') } }",
      d1Databases: ["DB"],
    }));
    db = await miniflare.getD1Database("DB") as unknown as D1Database;
    const statements = `${migrationSql}\n${writeOptimizationSql}`
      .replace(/^PRAGMA foreign_keys = ON;\s*/u, "")
      .split(";")
      .map((statement) => statement.trim())
      .filter(Boolean);
    for (const statement of statements) await db.prepare(statement).run();
  });

  afterEach(async () => {
    await miniflare.dispose();
  });

  it("rejects a reused challenge and enforces the rotate-key limit", async () => {
    const deviceId = "c".repeat(64);
    const challenge = encodeBase64Url(new Uint8Array(16));
    const nowSeconds = Math.floor(Date.UTC(2026, 8, 6) / 1000);
    await expect(consumeChallenge(db, deviceId, challenge, nowSeconds)).resolves.toBeUndefined();
    await expect(consumeChallenge(db, deviceId, challenge, nowSeconds)).rejects.toEqual(
      expect.objectContaining<Partial<AppError>>({ code: "replay_detected", status: 409 }),
    );

    const nowMilliseconds = nowSeconds * 1000;
    for (let request = 0; request < 3; request += 1) {
      await expect(enforceRateLimit(db, "rotate_key", { deviceId }, nowMilliseconds))
        .resolves.toBeUndefined();
    }
    await expect(enforceRateLimit(db, "rotate_key", { deviceId }, nowMilliseconds)).rejects.toEqual(
      expect.objectContaining<Partial<AppError>>({ code: "rate_limited", status: 429 }),
    );
  });

  it("uses native rate-limit bindings without creating D1 counter rows", async () => {
    const accepted = { limit: vi.fn(async () => ({ success: true })) } as unknown as RateLimit;
    const denied = { limit: vi.fn(async () => ({ success: false })) } as unknown as RateLimit;
    const deviceId = "d".repeat(64);

    await expect(enforceRateLimit(
      db,
      "subscription_content",
      { deviceId },
      Date.UTC(2026, 8, 6),
      { SUBSCRIPTION_DEVICE_RATE_LIMITER: accepted },
    )).resolves.toBeUndefined();
    expect(accepted.limit).toHaveBeenCalledOnce();

    await expect(enforceRateLimit(
      db,
      "subscription_content",
      { deviceId },
      Date.UTC(2026, 8, 6),
      { SUBSCRIPTION_DEVICE_RATE_LIMITER: denied },
    )).rejects.toEqual(expect.objectContaining<Partial<AppError>>({
      code: "rate_limited",
      status: 429,
    }));
    expect(denied.limit).toHaveBeenCalledOnce();

    const counters = await db.prepare("SELECT COUNT(*) AS count FROM crypto_rate_limits")
      .first<{ count: number }>();
    expect(counters?.count).toBe(0);
  });

  it("deletes expired replay and rate-limit rows but keeps current rows", async () => {
    const now = new Date("2026-09-06T12:00:00.000Z");
    const nowSeconds = Math.floor(now.getTime() / 1000);
    const currentMinute = Math.floor(now.getTime() / 60_000);
    await db.batch([
      db.prepare("INSERT INTO crypto_replay_nonces VALUES ('old', 'one', ?1, ?2)")
        .bind(nowSeconds - 1000, nowSeconds - 1),
      db.prepare("INSERT INTO crypto_replay_nonces VALUES ('new', 'two', ?1, ?2)")
        .bind(nowSeconds, nowSeconds + 600),
      db.prepare("INSERT INTO crypto_rate_limits VALUES ('old', 'one', ?1, 1)")
        .bind(currentMinute - 3),
      db.prepare("INSERT INTO crypto_rate_limits VALUES ('new', 'two', ?1, 1)")
        .bind(currentMinute),
    ]);

    await expect(cleanupExpiredSecurityState(db, now)).resolves.toEqual({
      replayNoncesDeleted: 1,
      rateLimitRowsDeleted: 1,
    });
    const replay = await db.prepare("SELECT device_id AS deviceId FROM crypto_replay_nonces").all<{ deviceId: string }>();
    const rates = await db.prepare("SELECT scope FROM crypto_rate_limits").all<{ scope: string }>();
    expect(replay.results).toEqual([{ deviceId: "new" }]);
    expect(rates.results).toEqual([{ scope: "new" }]);
  });
});
