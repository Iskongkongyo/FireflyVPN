import { AppError } from "../http/errors";

export const INPUT_LIMITS = {
  jsonBodyBytes: 64 * 1024,
  managedSubscriptionBytes: 2 * 1024 * 1024,
  externalResponseBytes: 2 * 1024 * 1024,
  displayNameCharacters: 64,
  sessionIdCharacters: 128,
  sourceNameCharacters: 100,
  sourceNoteCharacters: 200,
  urlCharacters: 2048,
} as const;

export async function readJsonBody(
  request: Request,
  maximumBytes = INPUT_LIMITS.jsonBodyBytes,
): Promise<unknown> {
  const contentType = request.headers.get("Content-Type")?.toLowerCase() ?? "";
  if (!contentType.startsWith("application/json")) {
    throw new AppError("invalid_request", 400);
  }

  const declaredLength = request.headers.get("Content-Length");
  if (declaredLength !== null) {
    const parsedLength = Number(declaredLength);
    if (!Number.isSafeInteger(parsedLength) || parsedLength < 0) {
      throw new AppError("invalid_request", 400);
    }
    if (parsedLength > maximumBytes) {
      throw new AppError("payload_too_large", 413);
    }
  }

  if (request.body === null) {
    throw new AppError("invalid_request", 400);
  }

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let totalBytes = 0;

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      totalBytes += value.byteLength;
      if (totalBytes > maximumBytes) {
        await reader.cancel();
        throw new AppError("payload_too_large", 413);
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const body = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }

  try {
    const text = new TextDecoder("utf-8", { fatal: true }).decode(body);
    return JSON.parse(text) as unknown;
  } catch (error) {
    if (error instanceof AppError) throw error;
    throw new AppError("invalid_request", 400);
  }
}
