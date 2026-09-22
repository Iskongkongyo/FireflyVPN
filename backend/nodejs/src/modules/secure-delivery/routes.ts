import type { Hono } from "hono";

import type { AppContext } from "../../app/context";
import { isBase64UrlBytes } from "../../foundation/crypto/base64url";
import { AppError } from "../../foundation/http/errors";
import { success } from "../../foundation/http/response";
import { getClientSubscriptionCatalog, getSubscriptionPlaintext } from "../subscription-catalog/service";
import { authenticateDevice } from "./authenticator";
import { encryptSubscription } from "./encryptor";
import {
  CHALLENGE_HEADER,
  CRYPTO_VERSION_HEADER,
  CRYPTO_V2_VERSION,
} from "./protocol";
import { enforceRateLimit } from "./rate-guard";
import { consumeChallenge } from "./replay-guard";

export function registerSecureDeliveryRoutes(app: Hono<AppContext>): void {
  app.get("/api/v2/subscriptions", async (context) => {
    await authenticateDevice(context.req.raw, context.env);
    return success(await getClientSubscriptionCatalog(context.env));
  });

  app.get("/api/v2/subscriptions/:id/content", async (context) => {
    const request = context.req.raw;
    if (request.headers.get(CRYPTO_VERSION_HEADER) !== String(CRYPTO_V2_VERSION)) {
      throw new AppError("unsupported_crypto_version", 400);
    }
    const challenge = request.headers.get(CHALLENGE_HEADER)?.trim() ?? "";
    if (!isBase64UrlBytes(challenge, 16)) {
      throw new AppError("invalid_challenge", 400);
    }

    const device = await authenticateDevice(request, context.env);
    await enforceRateLimit(context.env.DB, "subscription_content", {
      deviceId: device.deviceId,
    });
    await consumeChallenge(context.env.DB, device.deviceId, challenge);
    const { source, content } = await getSubscriptionPlaintext(context.env, context.req.param("id"));
    const envelope = await encryptSubscription({
      plaintext: content,
      clientPublicKeySpki: device.publicKeySpki,
      deviceId: device.deviceId,
      subscriptionId: source.id,
      challenge,
    });

    return new Response(JSON.stringify(envelope), {
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "no-store",
        "X-Firefly-Crypto-Version": String(CRYPTO_V2_VERSION),
      },
    });
  });
}
