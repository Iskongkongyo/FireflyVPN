const BASE64URL_PATTERN = /^[A-Za-z0-9_-]*$/;

export function encodeBase64Url(value: ArrayBuffer | Uint8Array): string {
  const bytes = value instanceof Uint8Array ? value : new Uint8Array(value);
  let binary = "";

  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  }

  return btoa(binary)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "");
}

export function decodeBase64Url(value: string): Uint8Array<ArrayBuffer> {
  if (!BASE64URL_PATTERN.test(value) || value.length % 4 === 1) {
    throw new TypeError("Invalid Base64URL value");
  }

  const padded = value.replace(/-/g, "+").replace(/_/g, "/")
    + "=".repeat((4 - (value.length % 4)) % 4);

  let binary: string;
  try {
    binary = atob(padded);
  } catch {
    throw new TypeError("Invalid Base64URL value");
  }

  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }

  if (encodeBase64Url(bytes) !== value) {
    throw new TypeError("Non-canonical Base64URL value");
  }
  return bytes;
}

export function isBase64UrlBytes(value: string, expectedLength: number): boolean {
  try {
    return decodeBase64Url(value).byteLength === expectedLength;
  } catch {
    return false;
  }
}
