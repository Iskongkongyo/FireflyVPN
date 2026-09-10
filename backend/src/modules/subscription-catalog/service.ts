import type { Env } from "../../app/env";
import { AppError } from "../../foundation/http/errors";
import { INPUT_LIMITS } from "../../foundation/security/input-limits";
import { readRuntimeConfig } from "../runtime-config/store";
import {
  CRYPTO_V2_ALGORITHM,
  CRYPTO_V2_VERSION,
} from "../secure-delivery/protocol";
import {
  clearSourceContent,
  managedContentKey,
  readSubscriptionContent,
} from "./content-store";
import type {
  SubscriptionSourceInput,
  SubscriptionSourceRecord,
} from "./models";
import {
  deleteSource,
  findSource,
  insertSource,
  listAllSources,
  listEnabledSources,
  updateSource,
} from "./repository";
import { validateExternalSourceUrl } from "./source-fetcher";
import { validateSubscriptionContent } from "./validator";

const SOURCE_ID_PATTERN = /^[a-z0-9][a-z0-9_-]{0,63}$/;

function object(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new AppError("invalid_request", 400);
  }
  return value as Record<string, unknown>;
}

function boundedText(value: unknown, maximum: number, required = false): string | null {
  if (value === undefined || value === null) {
    if (required) throw new AppError("invalid_request", 400);
    return null;
  }
  if (typeof value !== "string") throw new AppError("invalid_request", 400);
  const normalized = value.trim();
  if ((required && normalized.length === 0) || [...normalized].length > maximum) {
    throw new AppError("invalid_request", 400);
  }
  return normalized || null;
}

function integer(
  value: unknown,
  fallback: number,
  minimum: number,
  maximum: number,
): number {
  const result = value === undefined ? fallback : value;
  if (!Number.isSafeInteger(result) || Number(result) < minimum || Number(result) > maximum) {
    throw new AppError("invalid_request", 400);
  }
  return Number(result);
}

export function isValidSourceId(sourceId: string): boolean {
  return SOURCE_ID_PATTERN.test(sourceId);
}

function sourceToInput(
  value: unknown,
  current?: SubscriptionSourceRecord,
): SubscriptionSourceInput {
  const body = object(value);
  const idValue = current?.id ?? boundedText(body.id, 64, true);
  if (idValue === null || !SOURCE_ID_PATTERN.test(idValue)) {
    throw new AppError("invalid_request", 400);
  }
  const name = boundedText(
    body.name === undefined ? current?.name : body.name,
    INPUT_LIMITS.sourceNameCharacters,
    true,
  );
  const note = boundedText(
    body.note === undefined ? current?.note : body.note,
    INPUT_LIMITS.sourceNoteCharacters,
  );
  const sourceType = body.sourceType === undefined ? current?.sourceType : body.sourceType;
  if (sourceType !== "managed" && sourceType !== "external") {
    throw new AppError("invalid_request", 400);
  }
  const sourceUrl = boundedText(
    body.sourceUrl === undefined ? current?.sourceUrl : body.sourceUrl,
    INPUT_LIMITS.urlCharacters,
    sourceType === "external",
  );
  if (sourceType === "external" && sourceUrl !== null) {
    validateExternalSourceUrl(sourceUrl);
  }
  const enabledValue = body.enabled === undefined
    ? current === undefined || current.enabled === 1
    : body.enabled;
  if (typeof enabledValue !== "boolean") throw new AppError("invalid_request", 400);

  const managedContent = body.managedContent;
  if (managedContent !== undefined && typeof managedContent !== "string") {
    throw new AppError("invalid_request", 400);
  }
  if (typeof managedContent === "string") validateSubscriptionContent(managedContent);
  if (sourceType === "managed" && current === undefined && typeof managedContent !== "string") {
    throw new AppError("invalid_request", 400);
  }

  return {
    id: idValue,
    name: name!,
    note,
    sourceType,
    sourceUrl: sourceType === "external" ? sourceUrl : null,
    enabled: enabledValue,
    sortOrder: integer(body.sortOrder, current?.sortOrder ?? 0, -1_000_000, 1_000_000),
    cacheTtlSeconds: integer(
      body.cacheTtlSeconds,
      current?.cacheTtlSeconds ?? 300,
      30,
      86_400,
    ),
    ...(typeof managedContent === "string" ? { managedContent } : {}),
  };
}

export async function getClientSubscriptionCatalog(env: Env): Promise<Record<string, unknown>[]> {
  return (await listEnabledSources(env.DB)).map((source) => ({
    id: source.id,
    name: source.name,
    contentUrl: `/api/v2/subscriptions/${encodeURIComponent(source.id)}/content`,
    cryptoVersion: CRYPTO_V2_VERSION,
    algorithm: CRYPTO_V2_ALGORITHM,
  }));
}

export async function getSubscriptionPlaintext(
  env: Env,
  sourceId: string,
  forceRefresh = false,
): Promise<{ source: SubscriptionSourceRecord; content: string }> {
  if (!SOURCE_ID_PATTERN.test(sourceId)) throw new AppError("subscription_not_found", 404);
  const source = await findSource(env.DB, sourceId, true);
  if (source === null) throw new AppError("subscription_not_found", 404);
  const runtime = await readRuntimeConfig(env);
  const content = await readSubscriptionContent(
    source,
    env.CONFIG,
    runtime.settings.subscriptionFetchTimeoutMs,
    forceRefresh,
  );
  validateSubscriptionContent(content);
  return { source, content };
}

export async function getAdminSources(env: Env): Promise<SubscriptionSourceRecord[]> {
  return listAllSources(env.DB);
}

export async function getAdminSource(
  env: Env,
  sourceId: string,
): Promise<SubscriptionSourceRecord & { managedContent?: string }> {
  if (!SOURCE_ID_PATTERN.test(sourceId)) throw new AppError("subscription_not_found", 404);
  const source = await findSource(env.DB, sourceId);
  if (source === null) throw new AppError("subscription_not_found", 404);
  if (source.sourceType !== "managed") return source;

  const managedContent = await env.CONFIG.get(managedContentKey(source.id));
  if (managedContent === null) throw new AppError("subscription_unavailable", 503);
  return { ...source, managedContent };
}

export async function inspectAdminSource(
  env: Env,
  sourceId: string,
  forceRefresh = false,
): Promise<{ source: SubscriptionSourceRecord; content: string }> {
  if (!SOURCE_ID_PATTERN.test(sourceId)) throw new AppError("subscription_not_found", 404);
  const source = await findSource(env.DB, sourceId);
  if (source === null) throw new AppError("subscription_not_found", 404);
  const runtime = await readRuntimeConfig(env);
  const content = await readSubscriptionContent(
    source,
    env.CONFIG,
    runtime.settings.subscriptionFetchTimeoutMs,
    forceRefresh,
  );
  validateSubscriptionContent(content);
  return { source, content };
}

export async function createSubscriptionSource(
  env: Env,
  body: unknown,
): Promise<SubscriptionSourceRecord> {
  const input = sourceToInput(body);
  if (await findSource(env.DB, input.id) !== null) {
    throw new AppError("invalid_request", 409);
  }
  if (input.sourceType === "managed") {
    await env.CONFIG.put(managedContentKey(input.id), input.managedContent!);
  }
  try {
    await insertSource(env.DB, input, new Date().toISOString());
  } catch {
    if (input.sourceType === "managed") await clearSourceContent(env.CONFIG, input.id);
    throw new AppError("internal_error", 500);
  }
  return (await findSource(env.DB, input.id))!;
}

export async function patchSubscriptionSource(
  env: Env,
  sourceId: string,
  body: unknown,
): Promise<SubscriptionSourceRecord> {
  if (!SOURCE_ID_PATTERN.test(sourceId)) throw new AppError("subscription_not_found", 404);
  const current = await findSource(env.DB, sourceId);
  if (current === null) throw new AppError("subscription_not_found", 404);
  const input = sourceToInput(body, current);
  if (
    input.sourceType === "managed"
    && current.sourceType === "external"
    && input.managedContent === undefined
  ) {
    throw new AppError("invalid_request", 400);
  }

  if (input.sourceType === "managed" && input.managedContent !== undefined) {
    await env.CONFIG.put(managedContentKey(input.id), input.managedContent);
  }
  await updateSource(env.DB, input, new Date().toISOString());
  if (current.sourceType !== input.sourceType || current.sourceUrl !== input.sourceUrl) {
    await clearSourceContent(env.CONFIG, input.id);
    if (input.sourceType === "managed" && input.managedContent !== undefined) {
      await env.CONFIG.put(managedContentKey(input.id), input.managedContent);
    }
  }
  return (await findSource(env.DB, input.id))!;
}

export async function removeSubscriptionSource(env: Env, sourceId: string): Promise<void> {
  if (!SOURCE_ID_PATTERN.test(sourceId)) throw new AppError("subscription_not_found", 404);
  if (await findSource(env.DB, sourceId) === null) {
    throw new AppError("subscription_not_found", 404);
  }
  await deleteSource(env.DB, sourceId);
  await clearSourceContent(env.CONFIG, sourceId);
}
