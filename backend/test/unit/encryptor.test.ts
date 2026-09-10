import { describe, expect, it } from "vitest";

import vector from "../vectors/crypto-v2.json";
import { decodeBase64Url, encodeBase64Url } from "../../src/foundation/crypto/base64url";
import {
  decryptSubscription,
  deriveAesKeyBytes,
  deriveSharedSecret,
  encryptSubscription,
  importP256PrivateKey,
  importP256PublicKey,
} from "../../src/modules/secure-delivery/encryptor";
import type { CryptoV2Envelope } from "../../src/modules/secure-delivery/protocol";

async function vectorKeyPair(): Promise<CryptoKeyPair> {
  return {
    privateKey: await importP256PrivateKey(vector.serverEphemeralPrivateKeyPkcs8),
    publicKey: await importP256PublicKey(vector.serverEphemeralPublicKeySpki, true),
  };
}

async function vectorEnvelope(): Promise<CryptoV2Envelope> {
  return encryptSubscription({
    plaintext: new TextDecoder().decode(decodeBase64Url(vector.plaintextUtf8Base64Url)),
    clientPublicKeySpki: vector.clientPublicKeySpki,
    deviceId: vector.deviceId,
    subscriptionId: vector.subscriptionId,
    challenge: vector.challenge,
  }, {
    issuedAt: vector.issuedAt,
    ephemeralKeyPair: await vectorKeyPair(),
    salt: decodeBase64Url(vector.salt),
    nonce: decodeBase64Url(vector.nonce),
  });
}

function tamper(value: string): string {
  const bytes = decodeBase64Url(value);
  bytes[0] = (bytes[0] ?? 0) ^ 1;
  return encodeBase64Url(bytes);
}

describe("Crypto V2 encryptor", () => {
  it("matches the fixed ECDH and HKDF output", async () => {
    const privateKey = await importP256PrivateKey(vector.serverEphemeralPrivateKeyPkcs8);
    const publicKey = await importP256PublicKey(vector.clientPublicKeySpki);
    const sharedSecret = await deriveSharedSecret(privateKey, publicKey);
    const aesKey = await deriveAesKeyBytes(sharedSecret, decodeBase64Url(vector.salt));

    expect(encodeBase64Url(sharedSecret)).toBe(vector.sharedSecret);
    expect(encodeBase64Url(aesKey)).toBe(vector.aesKey);
  });

  it("matches and decrypts the cross-platform test vector", async () => {
    const envelope = await vectorEnvelope();

    expect(envelope.ciphertext).toBe(vector.ciphertext);
    expect(envelope.ephemeralPublicKey).toBe(vector.serverEphemeralPublicKeySpki);
    expect(envelope.expiresAt - envelope.issuedAt).toBe(300);
    await expect(decryptSubscription(envelope, vector.clientPrivateKeyPkcs8))
      .resolves.toBe(new TextDecoder().decode(decodeBase64Url(vector.plaintextUtf8Base64Url)));
  });

  it.each([
    ["ciphertext", (value: CryptoV2Envelope) => ({ ...value, ciphertext: tamper(value.ciphertext) })],
    ["nonce", (value: CryptoV2Envelope) => ({ ...value, nonce: tamper(value.nonce) })],
    ["AAD", (value: CryptoV2Envelope) => ({ ...value, subscriptionId: `${value.subscriptionId}-tampered` })],
  ] as const)("rejects tampered %s", async (_name, mutate) => {
    const envelope = mutate(await vectorEnvelope());
    await expect(decryptSubscription(envelope, vector.clientPrivateKeyPkcs8)).rejects.toThrow();
  });

  it("cannot be decrypted by another device private key", async () => {
    const other = await crypto.subtle.generateKey(
      { name: "ECDH", namedCurve: "P-256" },
      true,
      ["deriveBits"],
    );
    const otherPrivate = encodeBase64Url(await crypto.subtle.exportKey("pkcs8", other.privateKey));

    await expect(decryptSubscription(await vectorEnvelope(), otherPrivate)).rejects.toThrow();
  });
});
