import { decodeBase64Url } from "../../foundation/crypto/base64url";
import { sha256Base64Url } from "../../foundation/crypto/digest";
import { AppError } from "../../foundation/http/errors";

export const REPLAY_NONCE_TTL_SECONDS = 600;

function isUniqueConstraintError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error);
  return /unique constraint|constraint failed|primary key/iu.test(message);
}

export async function consumeChallenge(
  db: D1Database,
  deviceId: string,
  challenge: string,
  nowEpochSeconds = Math.floor(Date.now() / 1000),
): Promise<void> {
  const challengeHash = await sha256Base64Url(decodeBase64Url(challenge));
  try {
    await db.prepare(`
      INSERT INTO crypto_replay_nonces (
        device_id, challenge_hash, created_at, expires_at
      ) VALUES (?1, ?2, ?3, ?4)
    `).bind(
      deviceId,
      challengeHash,
      nowEpochSeconds,
      nowEpochSeconds + REPLAY_NONCE_TTL_SECONDS,
    ).run();
  } catch (error) {
    if (isUniqueConstraintError(error)) throw new AppError("replay_detected", 409);
    throw new AppError("internal_error", 500);
  }
}
