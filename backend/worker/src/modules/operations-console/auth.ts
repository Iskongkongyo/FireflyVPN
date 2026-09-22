import type { Env } from "../../app/env";
import { constantTimeEqual } from "../../foundation/crypto/constant-time";
import { sha256Base64Url } from "../../foundation/crypto/digest";
import { AppError } from "../../foundation/http/errors";
import { enforceRateLimit, requestClientIp } from "../secure-delivery/rate-guard";
import { readRuntimeConfig } from "../runtime-config/store";
import { readAdminAccess } from "./access-settings";
import { issueAdminJwt, verifyAdminJwt, type AdminSession } from "./jwt";

export async function requireAdminRoute(
  env: Env,
  routeParameter: string,
): Promise<void> {
  const access = await readAdminAccess(env);
  if (!routeParameter || routeParameter !== access.accessPath) {
    throw new AppError("not_found", 404);
  }
}

function bearerToken(request: Request): string {
  const authorization = request.headers.get("Authorization") ?? "";
  return authorization.startsWith("Bearer ") ? authorization.slice(7) : "";
}

async function adminCredentialVersion(env: Env): Promise<string> {
  const access = await readAdminAccess(env);
  return access.tokenHash ?? sha256Base64Url(String(env.ADMIN_TOKEN ?? ""));
}

export async function requireAdminToken(
  request: Request,
  env: Env,
  routeParameter: string,
): Promise<void> {
  const access = await readAdminAccess(env);
  if (!routeParameter || routeParameter !== access.accessPath) throw new AppError("not_found", 404);
  const security = (await readRuntimeConfig(env)).security;
  if (security.adminLoginRateLimitEnabled) {
    await enforceRateLimit(env.DB, "admin_login", {
      ip: `${routeParameter}\0${requestClientIp(request)}`,
    }, Date.now(), undefined, { ip: security.adminLoginRateLimitPerMinute });
  }
  const token = bearerToken(request);
  // Keep accepting existing longer deployment secrets while newly saved tokens are limited to 8–16.
  if (token.length < 8 || token.length > 512) {
    throw new AppError("unauthorized", 401);
  }
  const tokenMatches = access.tokenHash === null
    ? Boolean(env.ADMIN_TOKEN) && constantTimeEqual(token, String(env.ADMIN_TOKEN ?? ""))
    : constantTimeEqual(await sha256Base64Url(token), access.tokenHash);
  if (!tokenMatches) {
    throw new AppError("unauthorized", 401);
  }
}

export async function requireAdmin(
  request: Request,
  env: Env,
  routeParameter: string,
): Promise<AdminSession> {
  await requireAdminRoute(env, routeParameter);
  return verifyAdminJwt(env.DB, bearerToken(request), await adminCredentialVersion(env));
}

export async function issueAuthenticatedAdminJwt(env: Env): Promise<{
  token: string;
  expiresAt: string;
}> {
  return issueAdminJwt(env.DB, await adminCredentialVersion(env));
}
