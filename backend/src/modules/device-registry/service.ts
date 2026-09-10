import { constantTimeEqual } from "../../foundation/crypto/constant-time";
import { sha256Base64Url } from "../../foundation/crypto/digest";
import { decodeBase64Url, encodeBase64Url } from "../../foundation/crypto/base64url";
import { AppError } from "../../foundation/http/errors";
import { readBearerToken } from "../../foundation/security/bearer";
import { INPUT_LIMITS } from "../../foundation/security/input-limits";
import { importP256PublicKey } from "../secure-delivery/encryptor";
import {
  enforceRateLimit,
  requestClientIp,
} from "../secure-delivery/rate-guard";
import type {
  DevicePlatform,
  EnrollDeviceInput,
  EnrollDeviceResult,
  RotateDeviceKeyResult,
  SafeDevice,
} from "./models";
import {
  createAnonymousDevice,
  findDevice,
  listAccountDevices,
  revokeAccountDevice,
  rotateDeviceCredentials,
  touchDevice,
} from "./repository";
import type { AuthenticatedDevice } from "../secure-delivery/authenticator";

const DEVICE_ID_PATTERN = /^[a-f0-9]{64}$/;
const PLATFORMS = new Set<DevicePlatform>(["android", "windows", "macos"]);

function randomBytes(length: number): Uint8Array<ArrayBuffer> {
  return crypto.getRandomValues(new Uint8Array(length));
}

function randomHex(length: number): string {
  return Array.from(randomBytes(length), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function object(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new AppError("invalid_request", 400);
  }
  return value as Record<string, unknown>;
}

function normalizeDeviceName(value: unknown): string | null {
  if (value === undefined || value === null || value === "") return null;
  if (typeof value !== "string") throw new AppError("invalid_request", 400);
  const cleaned = value.replace(/[\u0000-\u001f\u007f-\u009f]/g, "").trim();
  if (cleaned.length === 0) return null;
  if ([...cleaned].length > INPUT_LIMITS.displayNameCharacters) {
    throw new AppError("invalid_request", 400);
  }
  return cleaned;
}

async function validateEnrollment(value: unknown): Promise<EnrollDeviceInput> {
  const body = object(value);
  if (typeof body.deviceId !== "string" || !DEVICE_ID_PATTERN.test(body.deviceId)) {
    throw new AppError("invalid_device_id", 400);
  }
  if (typeof body.platform !== "string" || !PLATFORMS.has(body.platform as DevicePlatform)) {
    throw new AppError("invalid_request", 400);
  }
  if (body.cryptoVersion !== undefined && body.cryptoVersion !== 2) {
    throw new AppError("unsupported_crypto_version", 400);
  }
  if (typeof body.publicKey !== "string" || body.publicKey.length > 256) {
    throw new AppError("invalid_public_key", 400);
  }
  try {
    await importP256PublicKey(body.publicKey);
  } catch {
    throw new AppError("invalid_public_key", 400);
  }

  return {
    deviceId: body.deviceId,
    platform: body.platform as DevicePlatform,
    deviceName: normalizeDeviceName(body.deviceName),
    publicKeySpki: body.publicKey,
  };
}

async function validatedPublicKey(value: unknown): Promise<string> {
  if (typeof value !== "string" || value.length > 256) {
    throw new AppError("invalid_public_key", 400);
  }
  try {
    await importP256PublicKey(value);
  } catch {
    throw new AppError("invalid_public_key", 400);
  }
  return value;
}

async function verifyExistingToken(
  request: Request,
  tokenHash: string,
): Promise<boolean> {
  const token = readBearerToken(request);
  if (token === null) return false;
  return constantTimeEqual(await sha256Base64Url(token), tokenHash);
}

export async function enrollDevice(
  request: Request,
  db: D1Database,
  untrustedBody: unknown,
  now = new Date(),
): Promise<EnrollDeviceResult> {
  const input = await validateEnrollment(untrustedBody);
  await enforceRateLimit(db, "device_enroll", {
    ip: requestClientIp(request),
    deviceId: input.deviceId,
  }, now.getTime());

  const existing = await findDevice(db, input.deviceId);
  if (existing !== null) {
    if (existing.accountStatus === "banned") {
      throw new AppError("account_banned", 403);
    }
    if (existing.accountStatus === "deleted") {
      throw new AppError("account_deleted", 403);
    }
    if (existing.status === "banned") {
      throw new AppError("device_banned", 403);
    }
    if (existing.status === "revoked") {
      throw new AppError("device_revoked", 403);
    }
    if (existing.publicKeySpki !== input.publicKeySpki) {
      // A reinstall keeps the hardware-derived device ID but necessarily
      // creates a new local P-256 key and loses the old bearer token. Once all
      // authoritative account/device restrictions above have passed, atomically
      // bind a fresh credential to the same device record. The previous token
      // stops working immediately.
      const deviceToken = encodeBase64Url(randomBytes(32));
      const tokenHash = await sha256Base64Url(deviceToken);
      const rebound = await rotateDeviceCredentials(
        db,
        existing.id,
        existing.tokenHash,
        input.publicKeySpki,
        tokenHash,
        now.toISOString(),
      );
      if (!rebound) throw new AppError("device_conflict", 409);
      return {
        accountId: existing.accountId,
        deviceId: existing.id,
        deviceToken,
        cryptoVersion: 2,
        alreadyEnrolled: true,
      };
    }
    if (!await verifyExistingToken(request, existing.tokenHash)) {
      throw new AppError("unauthorized", 401);
    }

    await touchDevice(db, existing.id, now.toISOString());
    return {
      accountId: existing.accountId,
      deviceId: existing.id,
      cryptoVersion: 2,
      alreadyEnrolled: true,
    };
  }

  const accountId = randomHex(32);
  const deviceToken = encodeBase64Url(randomBytes(32));
  const tokenHash = await sha256Base64Url(deviceToken);

  try {
    await createAnonymousDevice(
      db,
      accountId,
      input,
      tokenHash,
      now.toISOString(),
    );
  } catch {
    // A concurrent request may have won the unique device-id insert. Never
    // overwrite that device or disclose a token that was not persisted.
    if (await findDevice(db, input.deviceId) !== null) {
      throw new AppError("device_conflict", 409);
    }
    throw new AppError("internal_error", 500);
  }

  return {
    accountId,
    deviceId: input.deviceId,
    deviceToken,
    cryptoVersion: 2,
    alreadyEnrolled: false,
  };
}

export async function getAccountDevices(
  db: D1Database,
  accountId: string,
): Promise<SafeDevice[]> {
  const devices = await listAccountDevices(db, accountId);
  return Promise.all(devices.map(async (device) => ({
    id: device.id,
    platform: device.platform,
    displayName: device.displayName,
    status: device.status,
    publicKeyFingerprint: (await sha256Base64Url(decodeBase64Url(device.publicKeySpki))).slice(0, 16),
    cryptoVersion: device.cryptoVersion,
    createdAt: device.createdAt,
    updatedAt: device.updatedAt,
    lastSeenAt: device.lastSeenAt,
  })));
}

export async function revokeOwnedDevice(
  db: D1Database,
  authenticated: AuthenticatedDevice,
  targetDeviceId: string,
  now = new Date(),
): Promise<void> {
  if (!DEVICE_ID_PATTERN.test(targetDeviceId) || targetDeviceId === authenticated.deviceId) {
    throw new AppError("invalid_device_id", 400);
  }
  const revoked = await revokeAccountDevice(
    db,
    authenticated.accountId,
    targetDeviceId,
    authenticated.deviceId,
    now.toISOString(),
  );
  if (!revoked) throw new AppError("not_found", 404);
}

export async function rotateDeviceKey(
  request: Request,
  db: D1Database,
  authenticated: AuthenticatedDevice,
  targetDeviceId: string,
  untrustedBody: unknown,
  now = new Date(),
): Promise<RotateDeviceKeyResult> {
  if (!DEVICE_ID_PATTERN.test(targetDeviceId) || targetDeviceId !== authenticated.deviceId) {
    throw new AppError("invalid_device_id", 400);
  }
  const body = object(untrustedBody);
  const publicKey = await validatedPublicKey(body.publicKey);
  await enforceRateLimit(db, "rotate_key", {
    ip: requestClientIp(request),
    deviceId: authenticated.deviceId,
    token: authenticated.tokenHash,
  }, now.getTime());

  const deviceToken = encodeBase64Url(randomBytes(32));
  const tokenHash = await sha256Base64Url(deviceToken);
  const rotatedAt = now.toISOString();
  const rotated = await rotateDeviceCredentials(
    db,
    authenticated.deviceId,
    authenticated.tokenHash,
    publicKey,
    tokenHash,
    rotatedAt,
  );
  if (!rotated) throw new AppError("unauthorized", 401);
  return {
    deviceId: authenticated.deviceId,
    deviceToken,
    cryptoVersion: 2,
    rotatedAt,
  };
}
