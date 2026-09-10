import { describe, expect, it } from "vitest";

import {
  decodeBase64Url,
  encodeBase64Url,
  isBase64UrlBytes,
} from "../../src/foundation/crypto/base64url";

describe("Base64URL", () => {
  it("round trips binary data without padding", () => {
    const bytes = Uint8Array.from([0, 1, 2, 253, 254, 255]);
    const encoded = encodeBase64Url(bytes);

    expect(encoded).toBe("AAEC_f7_");
    expect([...decodeBase64Url(encoded)]).toEqual([...bytes]);
    expect(encoded).not.toContain("=");
  });

  it("rejects padding, invalid alphabet, and non-canonical data", () => {
    expect(() => decodeBase64Url("YQ==")).toThrow();
    expect(() => decodeBase64Url("a")).toThrow();
    expect(() => decodeBase64Url("AB")).toThrow();
  });

  it("validates decoded byte length", () => {
    expect(isBase64UrlBytes("EBESExQVFhcYGRobHB0eHw", 16)).toBe(true);
    expect(isBase64UrlBytes("EBESExQVFhcYGRobHB0eHw", 32)).toBe(false);
  });
});
