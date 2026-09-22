import { sha256Base64Url } from "../../foundation/crypto/digest";
import { AppError } from "../../foundation/http/errors";

export type RateLimitAction =
  | "admin_login"
  | "device_enroll"
  | "subscription_content"
  | "rotate_key";

type Dimension = "ip" | "deviceId";

export interface NativeRateLimitBindings {
  DEVICE_ENROLL_IP_RATE_LIMITER?: RateLimit;
  DEVICE_ENROLL_DEVICE_RATE_LIMITER?: RateLimit;
  SUBSCRIPTION_DEVICE_RATE_LIMITER?: RateLimit;
  ROTATE_DEVICE_RATE_LIMITER?: RateLimit;
}

type NativeRateLimitBindingName = keyof NativeRateLimitBindings;

export const RATE_LIMITS: Record<
  RateLimitAction,
  Partial<Record<Dimension, number>>
> = {
  admin_login: { ip: 3 },
  device_enroll: { ip: 30, deviceId: 6 },
  subscription_content: { deviceId: 60 },
  rotate_key: { deviceId: 3 },
};

const NATIVE_RATE_LIMITERS: Record<
  RateLimitAction,
  Partial<Record<Dimension, NativeRateLimitBindingName>>
> = {
  admin_login: {},
  device_enroll: {
    ip: "DEVICE_ENROLL_IP_RATE_LIMITER",
    deviceId: "DEVICE_ENROLL_DEVICE_RATE_LIMITER",
  },
  subscription_content: { deviceId: "SUBSCRIPTION_DEVICE_RATE_LIMITER" },
  rotate_key: { deviceId: "ROTATE_DEVICE_RATE_LIMITER" },
};

export type RateLimitIdentities = Partial<Record<Dimension, string>>;
export type RateLimitOverrides = Partial<Record<Dimension, number>>;

export async function enforceRateLimit(
  db: D1Database,
  action: RateLimitAction,
  identities: RateLimitIdentities,
  nowEpochMilliseconds = Date.now(),
  nativeBindings?: NativeRateLimitBindings,
  limitOverrides: RateLimitOverrides = {},
): Promise<void> {
  const windowStart = Math.floor(nowEpochMilliseconds / 60_000);
  const limits = { ...RATE_LIMITS[action], ...limitOverrides };

  for (const [dimension, limit] of Object.entries(limits) as [Dimension, number][]) {
    const identity = identities[dimension];
    if (!identity) continue;
    const identityHash = await sha256Base64Url(identity);
    const bindingName = NATIVE_RATE_LIMITERS[action][dimension];
    const nativeLimiter = bindingName === undefined ? undefined : nativeBindings?.[bindingName];
    if (nativeLimiter !== undefined) {
      const outcome = await nativeLimiter.limit({ key: identityHash });
      if (!outcome.success) throw new AppError("rate_limited", 429);
      continue;
    }

    // Miniflare tests and deployments without native bindings retain the D1
    // fallback so rate limiting never silently disappears.
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
}

export function requestClientIp(request: Request): string {
  return request.headers.get("CF-Connecting-IP")?.trim() || "unknown";
}
