import { describe, expect, it } from "vitest";

import { INPUT_LIMITS } from "../../src/foundation/security/input-limits";
import { validateSubscriptionContent } from "../../src/modules/subscription-catalog/validator";

describe("subscription content validator", () => {
  it.each([
    ["uri-list", "hy2://password@example.com:443#Firefly"],
    ["clash-yaml", "proxies:\n  - name: Firefly\n    type: ss"],
    ["json", JSON.stringify({ outbounds: [{ type: "direct" }] })],
    ["base64", "dmxlc3M6Ly90ZXN0QGV4YW1wbGUuY29t"],
  ] as const)("recognizes %s content", (format, content) => {
    expect(validateSubscriptionContent(content)).toMatchObject({ format });
  });

  it("rejects empty, HTML, and obvious JSON error responses", () => {
    expect(() => validateSubscriptionContent("  \n ")).toThrow();
    expect(() => validateSubscriptionContent("<!doctype html><title>502</title>")).toThrow();
    expect(() => validateSubscriptionContent(JSON.stringify({ error: "unauthorized" }))).toThrow();
  });

  it("rejects content over the configured byte limit", () => {
    expect(() => validateSubscriptionContent(
      "x".repeat(INPUT_LIMITS.managedSubscriptionBytes + 1),
    )).toThrow();
  });
});
