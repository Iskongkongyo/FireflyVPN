const NODE_URI_SCHEMES = new Set([
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
]);

const URI_SCHEME = /^([a-z][a-z0-9+.-]*):\/\//iu;

export function isSupportedNodeUri(value: string): boolean {
  const scheme = URI_SCHEME.exec(value.trim())?.[1]?.toLowerCase();
  return scheme !== undefined && NODE_URI_SCHEMES.has(scheme);
}

export function containsSupportedNodeUri(content: string): boolean {
  return content.split(/\r?\n/u).some(isSupportedNodeUri);
}
