import { sha256Base64Url } from "../../foundation/crypto/digest";
import { AppError } from "../../foundation/http/errors";

export type RateLimitAction =
  | "device_enroll"
  | "subscription_content"
  | "rotate_key";

type Dimension = "ip" | "deviceId" | "token";

export const RATE_LIMITS: Record<
  RateLimitAction,
  Partial<Record<Dimension, number>>
> = {
  device_enroll: { ip: 30, deviceId: 6 },
  subscription_content: { ip: 180, deviceId: 60, token: 60 },
  rotate_key: { ip: 10, deviceId: 3, token: 3 },
};

export type RateLimitIdentities = Partial<Record<Dimension, string>>;

export async function enforceRateLimit(
  db: D1Database,
  action: RateLimitAction,
  identities: RateLimitIdentities,
  nowEpochMilliseconds = Date.now(),
): Promise<void> {
  const windowStart = Math.floor(nowEpochMilliseconds / 60_000);
  const limits = RATE_LIMITS[action];

  for (const [dimension, limit] of Object.entries(limits) as [Dimension, number][]) {
    const identity = identities[dimension];
    if (!identity) continue;
    const identityHash = await sha256Base64Url(identity);
    const row = await db.prepare(`
      INSERT INTO crypto_rate_limits (
        scope, identity_hash, window_start, request_count
      ) VALUES (?1, ?2, ?3, 1)
      ON CONFLICT(scope, identity_hash, window_start) DO UPDATE SET
        request_count = request_count + 1
      RETURNING request_count AS requestCount
    `).bind(`${action}:${dimension}`, identityHash, windowStart)
      .first<{ requestCount: number }>();

    if ((row?.requestCount ?? limit + 1) > limit) {
      throw new AppError("rate_limited", 429);
    }
  }

  if (crypto.getRandomValues(new Uint8Array(1))[0] === 0) {
    try {
      await db.prepare("DELETE FROM crypto_rate_limits WHERE window_start < ?1")
        .bind(windowStart - 2)
        .run();
    } catch {
      // Cleanup is best-effort; it never changes the result of the current request.
    }
  }
}

export function requestClientIp(request: Request): string {
  return request.headers.get("CF-Connecting-IP")?.trim() || "unknown";
}
