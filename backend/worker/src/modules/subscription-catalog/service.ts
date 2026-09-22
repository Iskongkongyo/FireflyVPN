import type { Env } from "../../app/env";
import { AppError } from "../../foundation/http/errors";
import { INPUT_LIMITS } from "../../foundation/security/input-limits";
import { readRuntimeConfig } from "../runtime-config/store";
import {
  CRYPTO_V2_ALGORITHM,
  CRYPTO_V2_VERSION,
} from "../secure-delivery/protocol";
import {
  clearExternalSourceCache,
  clearSourceContent,
  deleteManagedSubscriptionContent,
  managedContentKey,
  readSubscriptionContent,
  writeManagedSubscriptionContent,
} from "./content-store";
import { validateManagedContentForMerge } from "./merger";
import type {
  SubscriptionMergeMode,
  SubscriptionSourceInput,
  SubscriptionSourceRecord,
} from "./models";
import {
  deleteSource,
  findSource,
  insertSource,
  listAllSources,
  listEnabledSources,
  listMonitoredExternalSources,
  recordExternalHealth,
  updateSource,
} from "./repository";
import { validateExternalSourceUrl } from "./source-fetcher";
import { validateSubscriptionContent } from "./validator";

const SOURCE_ID_PATTERN = /^[a-z0-9][a-z0-9_-]{0,63}$/;
const MERGE_MODES = new Set<SubscriptionMergeMode>([
  "external_first",
  "managed_first",
  "interleave_external_first",
  "interleave_managed_first",
]);

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
  if (body.sourceType !== undefined && body.sourceType !== "managed" && body.sourceType !== "external") {
    throw new AppError("invalid_request", 400);
  }
  const sourceType = body.sourceType ?? (body.sourceUrl ? "external" : current?.sourceType ?? "managed");
  const sourceUrl = sourceType === "external"
    ? boundedText(body.sourceUrl === undefined ? current?.sourceUrl : body.sourceUrl, INPUT_LIMITS.urlCharacters, true)
    : null;
  if (sourceType === "managed" && body.sourceUrl !== undefined && body.sourceUrl !== null && body.sourceUrl !== "") {
    throw new AppError("invalid_request", 400);
  }
  if (sourceUrl !== null) validateExternalSourceUrl(sourceUrl);
  const enabledValue = body.enabled === undefined
    ? current === undefined || current.enabled === 1
    : body.enabled;
  if (typeof enabledValue !== "boolean") throw new AppError("invalid_request", 400);
  const externalHealthValue = body.externalHealthEnabled === undefined
    ? current?.sourceType === "external" ? current.externalHealthEnabled === 1 : true
    : body.externalHealthEnabled;
  if (typeof externalHealthValue !== "boolean") throw new AppError("invalid_request", 400);
  const requestedMergeMode = body.mergeMode === undefined
    ? current?.mergeMode ?? "external_first"
    : body.mergeMode;
  if (typeof requestedMergeMode !== "string"
    || !MERGE_MODES.has(requestedMergeMode as SubscriptionMergeMode)) {
    throw new AppError("invalid_request", 400);
  }
  const mergeMode: SubscriptionMergeMode = sourceType === "external"
    ? requestedMergeMode as SubscriptionMergeMode
    : "external_first";

  const managedContent = body.managedContent === null ? "" : body.managedContent;
  if (managedContent !== undefined && typeof managedContent !== "string") {
    throw new AppError("invalid_request", 400);
  }
  if (typeof managedContent === "string" && managedContent.trim() !== "") {
    validateSubscriptionContent(managedContent);
    if (sourceType === "external") validateManagedContentForMerge(managedContent);
  }

  return {
    id: idValue,
    name: name!,
    note,
    sourceType,
    sourceUrl,
    enabled: enabledValue,
    sortOrder: integer(body.sortOrder, current?.sortOrder ?? 0, -1_000_000, 1_000_000),
    cacheTtlSeconds: integer(
      body.cacheTtlSeconds,
      current?.cacheTtlSeconds ?? 300,
      30,
      86_400,
    ),
    mergeMode,
    externalHealthEnabled: sourceType === "external" && externalHealthValue,
    ...(typeof managedContent === "string" ? { managedContent: managedContent.trim() ? managedContent : "" } : {}),
  };
}

function externalHealthReporter(env: Env, source: SubscriptionSourceRecord) {
  return source.externalHealthEnabled === 1
    ? async (outcome: Parameters<typeof recordExternalHealth>[2]) => {
        await recordExternalHealth(env.DB, source, outcome);
      }
    : undefined;
}

async function validateContentForMode(
  env: Env,
  input: SubscriptionSourceInput,
  current?: SubscriptionSourceRecord,
): Promise<void> {
  const managed = input.managedContent === undefined && current
    ? await env.CONFIG.get(managedContentKey(input.id))
    : input.managedContent;
  if (input.sourceType === "managed" && !managed?.trim()) {
    throw new AppError("invalid_request", 400);
  }
  if (input.sourceType === "external" && managed?.trim()) {
    validateManagedContentForMerge(managed);
  }
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
  const timeoutMilliseconds = source.sourceType === "external"
    ? (await readRuntimeConfig(env)).settings.subscriptionFetchTimeoutMs
    : 0;
  const content = await readSubscriptionContent(
    source,
    env.CONFIG,
    timeoutMilliseconds,
    forceRefresh,
    externalHealthReporter(env, source),
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
  const managedContent = await env.CONFIG.get(managedContentKey(source.id));
  return managedContent === null ? source : { ...source, managedContent };
}

export async function inspectAdminSource(
  env: Env,
  sourceId: string,
  forceRefresh = false,
): Promise<{ source: SubscriptionSourceRecord; content: string }> {
  if (!SOURCE_ID_PATTERN.test(sourceId)) throw new AppError("subscription_not_found", 404);
  const source = await findSource(env.DB, sourceId);
  if (source === null) throw new AppError("subscription_not_found", 404);
  if (forceRefresh && source.sourceType !== "external") throw new AppError("invalid_request", 400);
  const timeoutMilliseconds = source.sourceType === "external"
    ? (await readRuntimeConfig(env)).settings.subscriptionFetchTimeoutMs
    : 0;
  const content = await readSubscriptionContent(
    source,
    env.CONFIG,
    timeoutMilliseconds,
    forceRefresh,
    externalHealthReporter(env, source),
  );
  validateSubscriptionContent(content);
  return { source, content };
}

export async function monitorExternalSources(env: Env): Promise<{
  checked: number;
  healthy: number;
  unhealthy: number;
}> {
  const sources = await listMonitoredExternalSources(env.DB);
  if (sources.length === 0) return { checked: 0, healthy: 0, unhealthy: 0 };
  const timeoutMilliseconds = (await readRuntimeConfig(env)).settings.subscriptionFetchTimeoutMs;
  let healthy = 0;
  const concurrency = 4;
  for (let offset = 0; offset < sources.length; offset += concurrency) {
    const outcomes = await Promise.all(sources.slice(offset, offset + concurrency).map(async (source) => {
      try {
        const content = await readSubscriptionContent(
          source,
          env.CONFIG,
          timeoutMilliseconds,
          true,
          externalHealthReporter(env, source),
        );
        validateSubscriptionContent(content);
        return true;
      } catch {
        // The content reader records a safe error code for each failed check.
        return false;
      }
    }));
    healthy += outcomes.filter(Boolean).length;
  }
  return { checked: sources.length, healthy, unhealthy: sources.length - healthy };
}

export async function createSubscriptionSource(
  env: Env,
  body: unknown,
): Promise<SubscriptionSourceRecord> {
  const input = sourceToInput(body);
  await validateContentForMode(env, input);
  if (await findSource(env.DB, input.id) !== null) {
    throw new AppError("invalid_request", 409);
  }
  // A previously deleted source may have left KV keys behind if cleanup failed.
  await clearSourceContent(env.CONFIG, input.id);
  if (input.managedContent) {
    await writeManagedSubscriptionContent(env.CONFIG, input.id, input.managedContent);
  }
  try {
    await insertSource(env.DB, input, new Date().toISOString());
  } catch {
    if (input.managedContent) await clearSourceContent(env.CONFIG, input.id);
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
  await validateContentForMode(env, input, current);
  if (current.sourceUrl !== input.sourceUrl) {
    await clearExternalSourceCache(env.CONFIG, input.id);
  }
  const previousManaged = input.managedContent === undefined
    ? undefined
    : await env.CONFIG.get(managedContentKey(input.id));
  if (input.managedContent !== undefined) {
    if (input.managedContent === "") {
      await deleteManagedSubscriptionContent(env.CONFIG, input.id);
    } else {
      await writeManagedSubscriptionContent(env.CONFIG, input.id, input.managedContent);
    }
  }
  const nextTimestamp = new Date(Math.max(Date.now(), Date.parse(current.updatedAt) + 1)).toISOString();
  try {
    await updateSource(env.DB, input, nextTimestamp);
  } catch (error) {
    if (previousManaged !== undefined) {
      if (previousManaged === null) {
        await deleteManagedSubscriptionContent(env.CONFIG, input.id);
      } else {
        await writeManagedSubscriptionContent(env.CONFIG, input.id, previousManaged);
      }
    }
    throw error;
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
