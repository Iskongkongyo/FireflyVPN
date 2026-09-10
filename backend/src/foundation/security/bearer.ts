const BEARER_PATTERN = /^Bearer ([A-Za-z0-9_-]{40,128})$/;

export function readBearerToken(request: Request): string | null {
  const authorization = request.headers.get("Authorization") ?? "";
  return BEARER_PATTERN.exec(authorization)?.[1] ?? null;
}
