import { describe, expect, it } from "vitest";

import vector from "../vectors/crypto-v2.json";
import { encodeBase64Url } from "../../src/foundation/crypto/base64url";
import {
  buildAad,
  CRYPTO_V2_ALGORITHM,
  CRYPTO_V2_HKDF_INFO,
  CRYPTO_V2_VERSION,
} from "../../src/modules/secure-delivery/protocol";

describe("Crypto V2 protocol", () => {
  it("keeps all protocol constants fixed", () => {
    expect(CRYPTO_V2_VERSION).toBe(vector.version);
    expect(CRYPTO_V2_ALGORITHM).toBe(vector.algorithm);
    expect(CRYPTO_V2_HKDF_INFO).toBe("FireflyVPN-Subscription-V2");
  });

  it("builds the exact cross-platform AAD byte sequence", () => {
    const aad = buildAad({
      deviceId: vector.deviceId,
      subscriptionId: vector.subscriptionId,
      challenge: vector.challenge,
      issuedAt: vector.issuedAt,
      expiresAt: vector.expiresAt,
    });

    expect(encodeBase64Url(aad)).toBe(vector.aadUtf8Base64Url);
    expect(new TextDecoder().decode(aad)).toBe([
      "2",
      vector.algorithm,
      vector.deviceId,
      vector.subscriptionId,
      vector.challenge,
      String(vector.issuedAt),
      String(vector.expiresAt),
    ].join("\n"));
  });
});
