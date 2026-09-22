import { AppError } from "../../foundation/http/errors";
import { INPUT_LIMITS } from "../../foundation/security/input-limits";
import { validateSubscriptionContent } from "./validator";

const MAX_REDIRECTS = 5;
const RETRY_DELAYS_MS = [500, 1_500] as const;
const REDIRECT_STATUSES = new Set([301, 302, 303, 307, 308]);

class RetryableUpstreamError extends Error {}

function retryableStatus(status: number): boolean {
  return status === 408 || status === 429 || status >= 500;
}

function wait(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

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
    // URL.hostname includes brackets for IPv6 literals. Reject all literals so
    // loopback and private IPv6 addresses cannot bypass the hostname checks.
    (hostname.startsWith("[") && hostname.endsWith("]"))
    || hostname === "localhost"
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
  const initialUrl = validateExternalSourceUrl(sourceUrl);

  for (let attempt = 0; attempt <= RETRY_DELAYS_MS.length; attempt += 1) {
    try {
      return await fetchExternalSourceOnce(initialUrl, timeoutMilliseconds, fetcher);
    } catch (error) {
      if (!(error instanceof RetryableUpstreamError)) throw error;
      const retryDelay = RETRY_DELAYS_MS[attempt];
      if (retryDelay === undefined) throw new AppError("upstream_failed", 502);
      await wait(retryDelay);
    }
  }
  throw new AppError("upstream_failed", 502);
}

async function fetchExternalSourceOnce(
  initialUrl: URL,
  timeoutMilliseconds: number,
  fetcher: typeof fetch,
): Promise<string> {
  let url = initialUrl;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMilliseconds);

  try {
    for (let redirects = 0; ; redirects += 1) {
      const response = await fetcher(url, {
        method: "GET",
        redirect: "manual",
        signal: controller.signal,
        headers: {
          Accept: "text/plain, application/yaml, application/json;q=0.9, */*;q=0.1",
          "User-Agent": "FireflyVPN-Edge/2",
        },
      });
      if (REDIRECT_STATUSES.has(response.status)) {
        const location = response.headers.get("Location");
        if (redirects >= MAX_REDIRECTS || !location) {
          throw new AppError("upstream_failed", 502);
        }
        let nextUrl: URL;
        try {
          nextUrl = new URL(location, url);
        } catch {
          throw new AppError("upstream_failed", 502);
        }
        url = validateExternalSourceUrl(nextUrl.href);
        await response.body?.cancel();
        continue;
      }
      if (!response.ok) {
        await response.body?.cancel();
        if (retryableStatus(response.status)) {
          throw new RetryableUpstreamError(`Retryable HTTP status ${response.status}`);
        }
        throw new AppError("upstream_failed", 502);
      }
      if (response.url) validateExternalSourceUrl(response.url);
      const contentType = response.headers.get("Content-Type")?.toLowerCase() ?? "";
      if (contentType.includes("text/html")) {
        throw new AppError("subscription_unavailable", 502);
      }
      const content = await readLimitedText(response);
      validateSubscriptionContent(content);
      return content;
    }
  } catch (error) {
    if (error instanceof AppError || error instanceof RetryableUpstreamError) throw error;
    throw new RetryableUpstreamError("Network request failed");
  } finally {
    clearTimeout(timeout);
  }
}
