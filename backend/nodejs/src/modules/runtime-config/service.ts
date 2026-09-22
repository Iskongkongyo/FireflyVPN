import type { Env } from "../../app/env";
import { systemClock, type Clock } from "../../foundation/time/clock";
import {
  CRYPTO_V2_ALGORITHM,
  CRYPTO_V2_VERSION,
} from "../secure-delivery/protocol";
import { readRuntimeConfig } from "./store";

export async function buildBootstrap(
  env: Env,
  clock: Clock = systemClock,
): Promise<Record<string, unknown>> {
  const config = await readRuntimeConfig(env);
  const { subscriptionFetchTimeoutMs: _internalTimeout, ...publicSettings } = config.settings;

  return {
    crypto: {
      version: CRYPTO_V2_VERSION,
      algorithm: CRYPTO_V2_ALGORITHM,
    },
    notice: config.notice.enabled ? config.notice : null,
    appUpdate: config.appUpdate,
    pcAppUpdate: config.pcAppUpdate,
    settings: publicSettings,
    generatedAt: clock.now().toISOString(),
  };
}
