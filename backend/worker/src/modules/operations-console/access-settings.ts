import type { Env } from "../../app/env";
import { sha256Base64Url } from "../../foundation/crypto/digest";
import { AppError } from "../../foundation/http/errors";
import { readOrCreateJwtSecret, replaceJwtSecret } from "./jwt";

export const ADMIN_ACCESS_KEY = "admin:access:v1";
const ACCESS_PATH_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{7,127}$/u;
const TOKEN_HASH_PATTERN = /^[A-Za-z0-9_-]{43}$/u;

interface StoredAdminAccess {
  accessPath: string;
  tokenHash: string | null;
  updatedAt: string;
}

export interface AdminAccess {
  accessPath: string;
  tokenHash: string | null;
  tokenConfiguredInKv: boolean;
}

function environmentAccessPath(env: Env): string {
  return String(env.ADMIN_ROUTE ?? "").trim().replace(/^\/+|\/+$/gu, "");
}

export async function readAdminAccess(env: Env): Promise<AdminAccess> {
  const stored = await env.CONFIG.get<StoredAdminAccess>(ADMIN_ACCESS_KEY, "json");
  if (
    stored !== null
    && ACCESS_PATH_PATTERN.test(stored.accessPath)
    && (stored.tokenHash === null || TOKEN_HASH_PATTERN.test(stored.tokenHash))
  ) {
    return {
      accessPath: stored.accessPath,
      tokenHash: stored.tokenHash,
      tokenConfiguredInKv: stored.tokenHash !== null,
    };
  }
  return {
    accessPath: environmentAccessPath(env),
    tokenHash: null,
    tokenConfiguredInKv: false,
  };
}

function inputObject(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new AppError("invalid_request", 400);
  }
  return value as Record<string, unknown>;
}

export async function updateAdminAccess(
  env: Env,
  value: unknown,
  now = new Date(),
): Promise<{
  accessPath: string;
  adminTokenConfigured: boolean;
  tokenChanged: boolean;
  jwtSecretChanged: boolean;
}> {
  const input = inputObject(value);
  const accessPath = typeof input.accessPath === "string"
    ? input.accessPath.trim().replace(/^\/+|\/+$/gu, "")
    : "";
  if (!ACCESS_PATH_PATTERN.test(accessPath)) {
    throw new AppError("invalid_request", 400);
  }
  const current = await readAdminAccess(env);
  const newAdminToken = input.newAdminToken;
  if (newAdminToken !== undefined && typeof newAdminToken !== "string") {
    throw new AppError("invalid_request", 400);
  }
  const tokenChanged = typeof newAdminToken === "string" && newAdminToken.length > 0;
  if (tokenChanged && (newAdminToken.length < 8 || newAdminToken.length > 16)) {
    throw new AppError("invalid_request", 400);
  }
  const tokenHash = tokenChanged
    ? await sha256Base64Url(newAdminToken)
    : current.tokenHash;
  const newJwtSecret = input.newJwtSecret;
  if (newJwtSecret !== undefined && typeof newJwtSecret !== "string") {
    throw new AppError("invalid_request", 400);
  }
  const jwtSecretChanged = typeof newJwtSecret === "string" && newJwtSecret.length > 0;
  if (jwtSecretChanged) await replaceJwtSecret(env.DB, newJwtSecret, now);
  else await readOrCreateJwtSecret(env.DB, now);
  const stored: StoredAdminAccess = {
    accessPath,
    tokenHash,
    updatedAt: now.toISOString(),
  };
  await env.CONFIG.put(ADMIN_ACCESS_KEY, JSON.stringify(stored));
  return {
    accessPath,
    adminTokenConfigured: tokenHash !== null,
    tokenChanged,
    jwtSecretChanged,
  };
}
