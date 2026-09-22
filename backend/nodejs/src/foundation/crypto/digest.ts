import { encodeBase64Url } from "./base64url";

const encoder = new TextEncoder();

export async function sha256(value: string | Uint8Array): Promise<Uint8Array> {
  const bytes = typeof value === "string" ? encoder.encode(value) : value;
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return new Uint8Array(await crypto.subtle.digest("SHA-256", copy.buffer));
}

export async function sha256Base64Url(value: string | Uint8Array): Promise<string> {
  return encodeBase64Url(await sha256(value));
}
