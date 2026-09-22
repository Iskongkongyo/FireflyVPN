import { afterEach, beforeEach, describe, expect, it } from "vitest";

import migrationSql from "../../database/migrations/0001_bootstrap.sql?raw";
import writeOptimizationSql from "../../database/migrations/0006_d1_write_optimization.sql?raw";
import { encodeBase64Url } from "../../src/foundation/crypto/base64url";
import { AppError } from "../../src/foundation/http/errors";
import { cleanupExpiredSecurityState } from "../../src/modules/security-maintenance/service";
import { consumeChallenge } from "../../src/modules/secure-delivery/replay-guard";
import { enforceRateLimit } from "../../src/modules/secure-delivery/rate-guard";
import { NodeDatabase } from "../../src/node/database";

describe("security state maintenance", () => {
  let database: NodeDatabase;
  let db: Database;

  beforeEach(async () => {
    database = new NodeDatabase(":memory:");
    database.sqlite.exec(`${migrationSql}\n${writeOptimizationSql}`);
    db = database.asDatabase();
  });

  afterEach(() => database.close());

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
