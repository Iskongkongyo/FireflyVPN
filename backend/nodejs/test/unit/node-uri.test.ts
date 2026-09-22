import { describe, expect, it } from "vitest";

import { isSupportedNodeUri } from "../../src/modules/subscription-catalog/node-uri";

describe("subscription node URI schemes", () => {
  it.each([
    "anytls",
    "http",
    "https",
    "hysteria",
    "hysteria2",
    "hy2",
    "naive+https",
    "naive+quic",
    "shadowtls",
    "snell",
    "socks",
    "socks4",
    "socks4a",
    "socks5",
    "ss",
    "ssh",
    "trojan",
    "tuic",
    "vless",
    "vmess",
    "wg",
    "wireguard",
  ])("recognizes the %s scheme", (scheme) => {
    expect(isSupportedNodeUri(`${scheme}://credential@example.com:443#Node`)).toBe(true);
  });

  it.each(["ftp", "ssr", "trojan-go", "unknown"])("rejects the unsupported %s scheme", (scheme) => {
    expect(isSupportedNodeUri(`${scheme}://credential@example.com:443`)).toBe(false);
  });
});
