export const CRYPTO_V2_VERSION = 2 as const;
export const CRYPTO_V2_ALGORITHM = "P256-HKDF-SHA256-A256GCM" as const;
export const CRYPTO_V2_HKDF_INFO = "FireflyVPN-Subscription-V2" as const;
export const CRYPTO_V2_RESPONSE_TTL_SECONDS = 300 as const;
export const CRYPTO_V2_GCM_TAG_BITS = 128 as const;

export const DEVICE_ID_HEADER = "X-Firefly-Device-ID" as const;
export const CRYPTO_VERSION_HEADER = "X-Firefly-Crypto-Version" as const;
export const CHALLENGE_HEADER = "X-Firefly-Challenge" as const;

const textEncoder = new TextEncoder();

export interface AadInput {
  deviceId: string;
  subscriptionId: string;
  challenge: string;
  issuedAt: number;
  expiresAt: number;
}

export interface CryptoV2Envelope {
  version: typeof CRYPTO_V2_VERSION;
  algorithm: typeof CRYPTO_V2_ALGORITHM;
  deviceId: string;
  subscriptionId: string;
  ephemeralPublicKey: string;
  salt: string;
  nonce: string;
  ciphertext: string;
  requestId: string;
  issuedAt: number;
  expiresAt: number;
}

export function buildAad(input: AadInput): Uint8Array {
  const text = [
    String(CRYPTO_V2_VERSION),
    CRYPTO_V2_ALGORITHM,
    input.deviceId,
    input.subscriptionId,
    input.challenge,
    String(input.issuedAt),
    String(input.expiresAt),
  ].join("\n");

  return textEncoder.encode(text);
}
