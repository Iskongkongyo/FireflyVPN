export type DevicePlatform = "android" | "windows" | "macos";
export type DeviceStatus = "active" | "revoked" | "banned";
export type AccountStatus = "active" | "banned" | "deleted";

export interface DeviceRecord {
  id: string;
  accountId: string;
  platform: DevicePlatform;
  displayName: string | null;
  status: DeviceStatus;
  publicKeySpki: string;
  cryptoVersion: number;
  tokenHash: string;
  tokenIssuedAt: string;
  createdAt: string;
  updatedAt: string;
  lastSeenAt: string;
  accountStatus: AccountStatus;
}

export interface EnrollDeviceInput {
  deviceId: string;
  platform: DevicePlatform;
  deviceName: string | null;
  publicKeySpki: string;
}

export interface EnrollDeviceResult {
  accountId: string;
  deviceId: string;
  deviceToken?: string;
  cryptoVersion: 2;
  alreadyEnrolled: boolean;
}

export interface SafeDevice {
  id: string;
  platform: DevicePlatform;
  displayName: string | null;
  status: DeviceStatus;
  publicKeyFingerprint: string;
  cryptoVersion: number;
  createdAt: string;
  updatedAt: string;
  lastSeenAt: string;
}

export interface RotateDeviceKeyResult {
  deviceId: string;
  deviceToken: string;
  cryptoVersion: 2;
  rotatedAt: string;
}
