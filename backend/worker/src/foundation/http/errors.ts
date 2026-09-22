export type ErrorCode =
  | "invalid_request"
  | "invalid_device_id"
  | "invalid_public_key"
  | "unauthorized"
  | "account_banned"
  | "account_deleted"
  | "device_banned"
  | "device_revoked"
  | "device_conflict"
  | "subscription_not_found"
  | "subscription_unavailable"
  | "unsupported_subscription_format"
  | "unsupported_crypto_version"
  | "invalid_challenge"
  | "replay_detected"
  | "rate_limited"
  | "payload_too_large"
  | "upstream_failed"
  | "feature_disabled"
  | "not_found"
  | "internal_error";

export class AppError extends Error {
  constructor(
    readonly code: ErrorCode,
    readonly status: number,
    message = code,
  ) {
    super(message);
    this.name = "AppError";
  }
}

export function toAppError(error: unknown): AppError {
  return error instanceof AppError
    ? error
    : new AppError("internal_error", 500);
}
