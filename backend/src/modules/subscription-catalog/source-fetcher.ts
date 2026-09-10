import { AppError } from "../../foundation/http/errors";
import { INPUT_LIMITS } from "../../foundation/security/input-limits";
import { validateSubscriptionContent } from "./validator";

export function validateExternalSourceUrl(value: string): URL {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new AppError("invalid_request", 400);
  }
  if (url.protocol !== "https:" || url.username || url.password) {
    throw new AppError("invalid_request", 400);
  }

  const hostname = url.hostname.toLowerCase().replace(/\.$/u, "");
  if (
    hostname === "localhost"
    || hostname.endsWith(".localhost")
    || hostname.endsWith(".local")
    || hostname.endsWith(".internal")
    || /^(?:0|10|127|169\.254|192\.168)\./u.test(hostname)
    || /^172\.(?:1[6-9]|2\d|3[01])\./u.test(hostname)
    || hostname === "::1"
    || hostname.startsWith("fc")
    || hostname.startsWith("fd")
    || hostname.startsWith("fe80:")
  ) {
    throw new AppError("invalid_request", 400);
  }
  return url;
}

async function readLimitedText(response: Response): Promise<string> {
  const declaredLength = Number(response.headers.get("Content-Length"));
  if (Number.isFinite(declaredLength) && declaredLength > INPUT_LIMITS.externalResponseBytes) {
    throw new AppError("payload_too_large", 413);
  }
  if (response.body === null) throw new AppError("upstream_failed", 502);

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let totalBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      totalBytes += value.byteLength;
      if (totalBytes > INPUT_LIMITS.externalResponseBytes) {
        await reader.cancel();
        throw new AppError("payload_too_large", 413);
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw new AppError("subscription_unavailable", 502);
  }
}

export async function fetchExternalSource(
  sourceUrl: string,
  timeoutMilliseconds: number,
  fetcher: typeof fetch = fetch,
): Promise<string> {
  const url = validateExternalSourceUrl(sourceUrl);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMilliseconds);

  try {
    const response = await fetcher(url, {
      method: "GET",
      redirect: "follow",
      signal: controller.signal,
      headers: {
        Accept: "text/plain, application/yaml, application/json;q=0.9, */*;q=0.1",
        "User-Agent": "FireflyVPN-Edge/2",
      },
    });
    if (!response.ok) throw new AppError("upstream_failed", 502);
    if (response.url) validateExternalSourceUrl(response.url);
    const contentType = response.headers.get("Content-Type")?.toLowerCase() ?? "";
    if (contentType.includes("text/html")) {
      throw new AppError("subscription_unavailable", 502);
    }
    const content = await readLimitedText(response);
    validateSubscriptionContent(content);
    return content;
  } catch (error) {
    if (error instanceof AppError) throw error;
    throw new AppError("upstream_failed", 502);
  } finally {
    clearTimeout(timeout);
  }
}
