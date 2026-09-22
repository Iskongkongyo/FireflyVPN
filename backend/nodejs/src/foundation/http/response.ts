import type { ErrorCode } from "./errors";

const JSON_HEADERS = {
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
} as const;

export function success<T>(
  data: T,
  status = 200,
  headers?: HeadersInit,
): Response {
  return new Response(JSON.stringify({ ok: true, data }), {
    status,
    headers: { ...JSON_HEADERS, ...headers },
  });
}

export function failure(
  error: ErrorCode,
  requestId: string,
  status: number,
  headers?: HeadersInit,
): Response {
  return new Response(JSON.stringify({ ok: false, error, requestId }), {
    status,
    headers: { ...JSON_HEADERS, ...headers },
  });
}
