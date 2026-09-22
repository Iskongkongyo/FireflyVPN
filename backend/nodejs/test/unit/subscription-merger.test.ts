import { describe, expect, it } from "vitest";

import { mergeSubscriptionContent, validateManagedContentForMerge } from "../../src/modules/subscription-catalog/merger";

describe("subscription content merge", () => {
  const external = "vless://external@example.com:443#External";
  const managed = "ss://dGVzdA==#托管";

  it("passes through an external source when managed content is absent", () => {
    const json = '{"outbounds":[]}';
    expect(mergeSubscriptionContent(json, null)).toBe(json);
  });

  it("appends managed URI nodes after external URI nodes", () => {
    expect(mergeSubscriptionContent(`${external}\r\n`, `\n${managed}`))
      .toBe(`${external}\n${managed}`);
  });

  it("merges mixed sing-box URI lists including the Hysteria2 hy2 alias", () => {
    const mixedExternal = "hy2://password@example.com:443#Hysteria2\nss://external@example.com:443#SS";
    expect(mergeSubscriptionContent(mixedExternal, managed))
      .toBe(`${mixedExternal}\n${managed}`);
  });

  it("supports all external and managed ordering modes", () => {
    const externalNodes = "vless://external-1\nvless://external-2\nvless://external-3";
    const managedNodes = "ss://managed-1\nss://managed-2";

    expect(mergeSubscriptionContent(externalNodes, managedNodes, "external_first"))
      .toBe(`${externalNodes}\n${managedNodes}`);
    expect(mergeSubscriptionContent(externalNodes, managedNodes, "managed_first"))
      .toBe(`${managedNodes}\n${externalNodes}`);
    expect(mergeSubscriptionContent(externalNodes, managedNodes, "interleave_external_first"))
      .toBe("vless://external-1\nss://managed-1\nvless://external-2\nss://managed-2\nvless://external-3");
    expect(mergeSubscriptionContent(externalNodes, managedNodes, "interleave_managed_first"))
      .toBe("ss://managed-1\nvless://external-1\nss://managed-2\nvless://external-2\nvless://external-3");
  });

  it("decodes base64 node lists and preserves the external encoding", () => {
    const encodedExternal = btoa(external);
    const merged = mergeSubscriptionContent(encodedExternal, managed);
    expect(new TextDecoder().decode(Uint8Array.from(atob(merged), (char) => char.charCodeAt(0))))
      .toBe(`${external}\n${managed}`);
    expect(mergeSubscriptionContent(external, btoa("ss://managed@example.com:443")))
      .toBe(`${external}\nss://managed@example.com:443`);
  });

  it("rejects formats that cannot be safely combined", () => {
    expect(() => mergeSubscriptionContent("proxies:\n  - name: node", managed))
      .toThrow(expect.objectContaining({ code: "unsupported_subscription_format" }));
    expect(() => validateManagedContentForMerge('{"outbounds":[]}'))
      .toThrow(expect.objectContaining({ code: "unsupported_subscription_format" }));
  });

  it("rejects a combined response larger than the delivery limit", () => {
    const largeNode = `vless://${"a".repeat(1_100_000)}`;
    expect(() => mergeSubscriptionContent(largeNode, largeNode))
      .toThrow(expect.objectContaining({ code: "payload_too_large", status: 413 }));
  });
});
