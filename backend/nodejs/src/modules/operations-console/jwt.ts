import { decodeBase64Url, encodeBase64Url } from "../../foundation/crypto/base64url";
import { AppError } from "../../foundation/http/errors";

const encoder = new TextEncoder();
const decoder = new TextDecoder();
const JWT_ISSUER = "firefly-vpn-edge";
const JWT_AUDIENCE = "firefly-admin-console";
const JWT_SUBJECT = "admin";
const JWT_TTL_SECONDS = 30 * 24 * 60 * 60;
const JWT_SECRET_MIN_LENGTH = 32;
const JWT_SECRET_MAX_LENGTH = 128;

interface JwtClaims {
  iss: string;
  aud: string;
  sub: string;
  iat: number;
  exp: number;
  jti: string;
  ver: string;
}

export interface AdminSession {
  jti: string;
  expiresAt: string;
}

function unauthorized(): never {
  throw new AppError("unauthorized", 401);
}

async function signingKey(secret: string): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign", "verify"],
  );
}

function encodeJson(value: unknown): string {
  return encodeBase64Url(encoder.encode(JSON.stringify(value)));
}

function decodeJson<T>(value: string): T {
  return JSON.parse(decoder.decode(decodeBase64Url(value))) as T;
}

export function validateJwtSecret(value: unknown): string {
  if (typeof value !== "string") throw new AppError("invalid_request", 400);
  const secret = value.trim();
  if (
    secret.length < JWT_SECRET_MIN_LENGTH
    || secret.length > JWT_SECRET_MAX_LENGTH
    || /[\u0000-\u001f\u007f]/u.test(secret)
  ) {
    throw new AppError("invalid_request", 400);
  }
  return secret;
}

export async function readOrCreateJwtSecret(
  db: Database,
  now = new Date(),
): Promise<{ secret: string; customized: boolean }> {
  const existing = await db.prepare(`
    SELECT jwt_secret AS secret, jwt_secret_source AS source
    FROM admin_security_settings WHERE id = 1
  `).first<{ secret: string; source: string }>();
  if (existing?.secret) {
    return { secret: existing.secret, customized: existing.source === "custom" };
  }
  const generatedSecret = crypto.randomUUID();
  await db.prepare(`
    INSERT OR IGNORE INTO admin_security_settings (
      id, jwt_secret, jwt_secret_source, updated_at
    ) VALUES (1, ?1, 'generated', ?2)
  `).bind(generatedSecret, now.toISOString()).run();
  const row = await db.prepare(`
    SELECT jwt_secret AS secret, jwt_secret_source AS source
    FROM admin_security_settings WHERE id = 1
  `).first<{ secret: string; source: string }>();
  if (!row?.secret) throw new AppError("internal_error", 500);
  return { secret: row.secret, customized: row.source === "custom" };
}

export async function replaceJwtSecret(
  db: Database,
  value: unknown,
  now = new Date(),
): Promise<void> {
  const secret = validateJwtSecret(value);
  await readOrCreateJwtSecret(db, now);
  await db.prepare(`
    UPDATE admin_security_settings
    SET jwt_secret = ?1, jwt_secret_source = 'custom', updated_at = ?2
    WHERE id = 1
  `).bind(secret, now.toISOString()).run();
  await db.prepare("DELETE FROM admin_revoked_sessions").run();
}

export async function issueAdminJwt(
  db: Database,
  credentialVersion: string,
  now = new Date(),
): Promise<{ token: string; expiresAt: string }> {
  const { secret } = await readOrCreateJwtSecret(db, now);
  const issuedAt = Math.floor(now.getTime() / 1000);
  const claims: JwtClaims = {
    iss: JWT_ISSUER,
    aud: JWT_AUDIENCE,
    sub: JWT_SUBJECT,
    iat: issuedAt,
    exp: issuedAt + JWT_TTL_SECONDS,
    jti: crypto.randomUUID(),
    ver: credentialVersion,
  };
  const header = encodeJson({ alg: "HS256", typ: "JWT" });
  const payload = encodeJson(claims);
  const unsigned = `${header}.${payload}`;
  const signature = await crypto.subtle.sign("HMAC", await signingKey(secret), encoder.encode(unsigned));
  return {
    token: `${unsigned}.${encodeBase64Url(signature)}`,
    expiresAt: new Date(claims.exp * 1000).toISOString(),
  };
}

export async function verifyAdminJwt(
  db: Database,
  token: string,
  credentialVersion: string,
  now = new Date(),
): Promise<AdminSession> {
  try {
    const parts = token.split(".");
    if (parts.length !== 3) return unauthorized();
    const headerPart = parts[0]!;
    const payloadPart = parts[1]!;
    const signaturePart = parts[2]!;
    const header = decodeJson<{ alg?: unknown; typ?: unknown }>(headerPart);
    const claims = decodeJson<Partial<JwtClaims>>(payloadPart);
    if (header.alg !== "HS256" || header.typ !== "JWT") return unauthorized();
    const { secret } = await readOrCreateJwtSecret(db, now);
    const verified = await crypto.subtle.verify(
      "HMAC",
      await signingKey(secret),
      decodeBase64Url(signaturePart),
      encoder.encode(`${headerPart}.${payloadPart}`),
    );
    const currentSeconds = Math.floor(now.getTime() / 1000);
    if (
      !verified
      || claims.iss !== JWT_ISSUER
      || claims.aud !== JWT_AUDIENCE
      || claims.sub !== JWT_SUBJECT
      || typeof claims.iat !== "number"
      || typeof claims.exp !== "number"
      || typeof claims.jti !== "string"
      || claims.ver !== credentialVersion
      || claims.jti.length < 8
      || claims.iat > currentSeconds + 60
      || claims.exp <= currentSeconds
      || claims.exp - claims.iat > JWT_TTL_SECONDS + 60
    ) return unauthorized();
    const revoked = await db.prepare(`
      SELECT 1 AS revoked FROM admin_revoked_sessions
      WHERE jti = ?1 AND expires_at > ?2 LIMIT 1
    `).bind(claims.jti, now.toISOString()).first<{ revoked: number }>();
    if (revoked) return unauthorized();
    return { jti: claims.jti, expiresAt: new Date(claims.exp * 1000).toISOString() };
  } catch (error) {
    if (error instanceof AppError) throw error;
    return unauthorized();
  }
}

export async function revokeAdminJwt(
  db: Database,
  session: AdminSession,
  now = new Date(),
): Promise<void> {
  await db.batch([
    db.prepare(`
      INSERT OR REPLACE INTO admin_revoked_sessions (jti, expires_at, revoked_at)
      VALUES (?1, ?2, ?3)
    `).bind(session.jti, session.expiresAt, now.toISOString()),
    db.prepare("DELETE FROM admin_revoked_sessions WHERE expires_at <= ?1").bind(now.toISOString()),
  ]);
}
