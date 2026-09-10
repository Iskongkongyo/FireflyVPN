import { AppError } from "../../foundation/http/errors";
import { INPUT_LIMITS } from "../../foundation/security/input-limits";
import type { SubscriptionContentSummary } from "./models";

const URI_LINE = /^(?:vmess|vless|trojan|ss|hysteria|hysteria2|tuic):\/\//imu;
const BASE64_TEXT = /^[A-Za-z0-9+/_=-]+$/u;

export function validateSubscriptionContent(
  content: string,
): SubscriptionContentSummary {
  const bytes = new TextEncoder().encode(content).byteLength;
  if (bytes === 0 || content.trim().length === 0) {
    throw new AppError("subscription_unavailable", 400);
  }
  if (bytes > INPUT_LIMITS.managedSubscriptionBytes) {
    throw new AppError("payload_too_large", 413);
  }

  const trimmed = content.trimStart();
  const beginning = trimmed.slice(0, 1024).toLowerCase();
  if (
    beginning.startsWith("<!doctype html")
    || beginning.startsWith("<html")
    || /<body(?:\s|>)/u.test(beginning)
  ) {
    throw new AppError("subscription_unavailable", 400);
  }

  let format: SubscriptionContentSummary["format"] = "text";
  if (URI_LINE.test(content)) {
    format = "uri-list";
  } else if (/^(?:proxies|proxy-groups|mixed-port|port):\s*/imu.test(content)) {
    format = "clash-yaml";
  } else if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
    try {
      const parsed = JSON.parse(trimmed) as unknown;
      if (parsed !== null && typeof parsed === "object" && !Array.isArray(parsed)) {
        const value = parsed as Record<string, unknown>;
        const looksLikeError = typeof value.error === "string"
          || (typeof value.status === "number" && value.status >= 400)
          || (typeof value.statusCode === "number" && value.statusCode >= 400);
        const looksLikeSubscription = "proxies" in value || "outbounds" in value;
        if (looksLikeError && !looksLikeSubscription) {
          throw new AppError("subscription_unavailable", 400);
        }
      }
      format = "json";
    } catch (error) {
      if (error instanceof AppError) throw error;
      format = "text";
    }
  } else {
    const compact = trimmed.replace(/\s+/gu, "");
    if (compact.length >= 16 && BASE64_TEXT.test(compact)) format = "base64";
  }

  return {
    bytes,
    lines: content.split(/\r?\n/u).length,
    format,
  };
}
