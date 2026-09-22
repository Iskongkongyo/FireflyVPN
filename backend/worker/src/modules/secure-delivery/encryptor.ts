import { decodeBase64Url, encodeBase64Url } from "../../foundation/crypto/base64url";
import {
  buildAad,
  CRYPTO_V2_ALGORITHM,
  CRYPTO_V2_GCM_TAG_BITS,
  CRYPTO_V2_HKDF_INFO,
  CRYPTO_V2_RESPONSE_TTL_SECONDS,
  CRYPTO_V2_VERSION,
  type CryptoV2Envelope,
} from "./protocol";

const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder("utf-8", { fatal: true });

function toArrayBuffer(value: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(value.byteLength);
  copy.set(value);
  return copy.buffer;
}

export interface EncryptSubscriptionInput {
  plaintext: string;
  clientPublicKeySpki: string;
  deviceId: string;
  subscriptionId: string;
  challenge: string;
}

export interface EncryptionMaterials {
  issuedAt?: number;
  ephemeralKeyPair?: CryptoKeyPair;
  salt?: Uint8Array;
  nonce?: Uint8Array;
}

function randomBytes(length: number): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(length));
}

function assertLength(value: Uint8Array, expected: number, name: string): void {
  if (value.byteLength !== expected) {
    throw new TypeError(`${name} must contain exactly ${expected} bytes`);
  }
}

export async function importP256PublicKey(
  encoded: string,
  extractable = false,
): Promise<CryptoKey> {
  const bytes = decodeBase64Url(encoded);
  if (bytes.byteLength < 80 || bytes.byteLength > 120) {
    throw new TypeError("Invalid P-256 SPKI public key");
  }

  try {
    return await crypto.subtle.importKey(
      "spki",
      toArrayBuffer(bytes),
      { name: "ECDH", namedCurve: "P-256" },
      extractable,
      [],
    );
  } catch {
    throw new TypeError("Invalid P-256 SPKI public key");
  }
}

export async function importP256PrivateKey(encoded: string): Promise<CryptoKey> {
  try {
    return await crypto.subtle.importKey(
      "pkcs8",
      toArrayBuffer(decodeBase64Url(encoded)),
      { name: "ECDH", namedCurve: "P-256" },
      false,
      ["deriveBits"],
    );
  } catch {
    throw new TypeError("Invalid P-256 PKCS8 private key");
  }
}

export async function deriveSharedSecret(
  privateKey: CryptoKey,
  publicKey: CryptoKey,
): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.deriveBits(
    { name: "ECDH", public: publicKey },
    privateKey,
    256,
  ));
}

export async function deriveAesKeyBytes(
  sharedSecret: Uint8Array,
  salt: Uint8Array,
): Promise<Uint8Array> {
  assertLength(salt, 32, "salt");
  const inputKey = await crypto.subtle.importKey(
    "raw",
    toArrayBuffer(sharedSecret),
    "HKDF",
    false,
    ["deriveBits"],
  );
  const bits = await crypto.subtle.deriveBits(
    {
      name: "HKDF",
      hash: "SHA-256",
      salt: toArrayBuffer(salt),
      info: toArrayBuffer(textEncoder.encode(CRYPTO_V2_HKDF_INFO)),
    },
    inputKey,
    256,
  );
  return new Uint8Array(bits);
}

async function importAesKey(keyBytes: Uint8Array, usage: KeyUsage): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    "raw",
    toArrayBuffer(keyBytes),
    { name: "AES-GCM", length: 256 },
    false,
    [usage],
  );
}

export async function encryptSubscription(
  input: EncryptSubscriptionInput,
  materials: EncryptionMaterials = {},
): Promise<CryptoV2Envelope> {
  const clientPublicKey = await importP256PublicKey(input.clientPublicKeySpki);
  const ephemeralKeyPair = materials.ephemeralKeyPair
    ?? await crypto.subtle.generateKey(
      { name: "ECDH", namedCurve: "P-256" },
      true,
      ["deriveBits"],
    );
  const salt = materials.salt?.slice() ?? randomBytes(32);
  const nonce = materials.nonce?.slice() ?? randomBytes(12);
  assertLength(salt, 32, "salt");
  assertLength(nonce, 12, "nonce");

  const issuedAt = materials.issuedAt ?? Math.floor(Date.now() / 1000);
  if (!Number.isSafeInteger(issuedAt) || issuedAt < 0) {
    throw new TypeError("issuedAt must be a non-negative integer");
  }
  const expiresAt = issuedAt + CRYPTO_V2_RESPONSE_TTL_SECONDS;
  const aad = buildAad({
    deviceId: input.deviceId,
    subscriptionId: input.subscriptionId,
    challenge: input.challenge,
    issuedAt,
    expiresAt,
  });

  const sharedSecret = await deriveSharedSecret(
    ephemeralKeyPair.privateKey,
    clientPublicKey,
  );
  const keyBytes = await deriveAesKeyBytes(sharedSecret, salt);
  const aesKey = await importAesKey(keyBytes, "encrypt");
  const ciphertext = await crypto.subtle.encrypt(
    {
      name: "AES-GCM",
      iv: toArrayBuffer(nonce),
      additionalData: toArrayBuffer(aad),
      tagLength: CRYPTO_V2_GCM_TAG_BITS,
    },
    aesKey,
    toArrayBuffer(textEncoder.encode(input.plaintext)),
  );
  const ephemeralPublicKey = await crypto.subtle.exportKey(
    "spki",
    ephemeralKeyPair.publicKey,
  );

  return {
    version: CRYPTO_V2_VERSION,
    algorithm: CRYPTO_V2_ALGORITHM,
    deviceId: input.deviceId,
    subscriptionId: input.subscriptionId,
    ephemeralPublicKey: encodeBase64Url(ephemeralPublicKey),
    salt: encodeBase64Url(salt),
    nonce: encodeBase64Url(nonce),
    ciphertext: encodeBase64Url(ciphertext),
    requestId: input.challenge,
    issuedAt,
    expiresAt,
  };
}

export async function decryptSubscription(
  envelope: CryptoV2Envelope,
  clientPrivateKeyPkcs8: string,
): Promise<string> {
  if (
    envelope.version !== CRYPTO_V2_VERSION
    || envelope.algorithm !== CRYPTO_V2_ALGORITHM
  ) {
    throw new TypeError("Unsupported Crypto V2 envelope");
  }

  const privateKey = await importP256PrivateKey(clientPrivateKeyPkcs8);
  const ephemeralPublicKey = await importP256PublicKey(envelope.ephemeralPublicKey);
  const salt = decodeBase64Url(envelope.salt);
  const nonce = decodeBase64Url(envelope.nonce);
  assertLength(salt, 32, "salt");
  assertLength(nonce, 12, "nonce");
  const sharedSecret = await deriveSharedSecret(privateKey, ephemeralPublicKey);
  const keyBytes = await deriveAesKeyBytes(sharedSecret, salt);
  const aesKey = await importAesKey(keyBytes, "decrypt");
  const aad = buildAad({
    deviceId: envelope.deviceId,
    subscriptionId: envelope.subscriptionId,
    challenge: envelope.requestId,
    issuedAt: envelope.issuedAt,
    expiresAt: envelope.expiresAt,
  });
  const plaintext = await crypto.subtle.decrypt(
    {
      name: "AES-GCM",
      iv: toArrayBuffer(nonce),
      additionalData: toArrayBuffer(aad),
      tagLength: CRYPTO_V2_GCM_TAG_BITS,
    },
    aesKey,
    toArrayBuffer(decodeBase64Url(envelope.ciphertext)),
  );
  return textDecoder.decode(plaintext);
}
