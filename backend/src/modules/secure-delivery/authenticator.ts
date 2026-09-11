import type { Env } from "../../app/env";
import { constantTimeEqual } from "../../foundation/crypto/constant-time";
import { sha256Base64Url } from "../../foundation/crypto/digest";
import { AppError } from "../../foundation/http/errors";
import { readBearerToken } from "../../foundation/security/bearer";
import type { DevicePlatform } from "../device-registry/models";
import { findDevice, touchDeviceIfStale } from "../device-registry/repository";
import { DEVICE_ID_HEADER } from "./protocol";

const DEVICE_ID_PATTERN = /^[a-f0-9]{64}$/;

export interface AuthenticatedDevice {
  accountId: string;
  deviceId: string;
  platform: DevicePlatform;
  publicKeySpki: string;
  tokenHash: string;
}

export async function authenticateDevice(
  request: Request,
  env: Env,
  now = new Date(),
): Promise<AuthenticatedDevice> {
  const deviceId = request.headers.get(DEVICE_ID_HEADER)?.trim() ?? "";
  const token = readBearerToken(request);
  if (!DEVICE_ID_PATTERN.test(deviceId) || token === null) {
    throw new AppError("unauthorized", 401);
  }

  const device = await findDevice(env.DB, deviceId);
  if (device === null) throw new AppError("unauthorized", 401);
  if (device.accountStatus === "banned") throw new AppError("account_banned", 403);
  if (device.accountStatus === "deleted") throw new AppError("account_deleted", 403);
  if (device.status === "banned") throw new AppError("device_banned", 403);
  if (device.status === "revoked") throw new AppError("device_revoked", 403);
  if (!constantTimeEqual(await sha256Base64Url(token), device.tokenHash)) {
    throw new AppError("unauthorized", 401);
  }

  await touchDeviceIfStale(env.DB, deviceId, device.lastSeenAt, now);
  return {
    accountId: device.accountId,
    deviceId: device.id,
    platform: device.platform,
    publicKeySpki: device.publicKeySpki,
    tokenHash: device.tokenHash,
  };
}
