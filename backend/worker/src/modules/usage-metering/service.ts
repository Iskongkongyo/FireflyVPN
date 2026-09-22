import { AppError } from "../../foundation/http/errors";
import { INPUT_LIMITS } from "../../foundation/security/input-limits";
import { businessPeriodKeys } from "../../foundation/time/clock";
import type { AuthenticatedDevice } from "../secure-delivery/authenticator";
import type { UsageReportInput, UsageReportResult } from "./models";
import { aggregateUsage, insertUsageSession } from "./repository";

const MAX_BYTES_PER_REPORT = 10 * 1024 * 1024 * 1024 * 1024;
const SESSION_ID_PATTERN = /^[A-Za-z0-9._:-]+$/;

function parseReport(value: unknown): UsageReportInput {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new AppError("invalid_request", 400);
  }
  const body = value as Record<string, unknown>;
  if (
    typeof body.sessionId !== "string"
    || body.sessionId.length === 0
    || body.sessionId.length > INPUT_LIMITS.sessionIdCharacters
    || !SESSION_ID_PATTERN.test(body.sessionId)
  ) {
    throw new AppError("invalid_request", 400);
  }

  for (const field of ["uploadBytes", "downloadBytes"] as const) {
    const bytes = body[field];
    if (!Number.isSafeInteger(bytes) || Number(bytes) < 0 || Number(bytes) > MAX_BYTES_PER_REPORT) {
      throw new AppError("invalid_request", 400);
    }
  }
  return {
    sessionId: body.sessionId,
    uploadBytes: Number(body.uploadBytes),
    downloadBytes: Number(body.downloadBytes),
  };
}

export async function reportUsage(
  db: D1Database,
  device: AuthenticatedDevice,
  untrustedBody: unknown,
  now = new Date(),
): Promise<UsageReportResult> {
  const report = parseReport(untrustedBody);
  const nowIso = now.toISOString();
  const inserted = await insertUsageSession(db, device, report, nowIso);
  if (!inserted) return { duplicate: true, sessionId: report.sessionId };

  const { day, month } = businessPeriodKeys(now);
  await aggregateUsage(db, device, report, day, month);
  return { duplicate: false, sessionId: report.sessionId };
}
