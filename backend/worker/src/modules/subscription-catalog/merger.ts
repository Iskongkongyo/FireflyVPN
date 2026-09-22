import { AppError } from "../../foundation/http/errors";
import type { SubscriptionMergeMode } from "./models";
import { isSupportedNodeUri } from "./node-uri";
import { validateSubscriptionContent } from "./validator";

function unsupportedFormat(): never {
  throw new AppError("unsupported_subscription_format", 422);
}

function decodedBase64(content: string): string {
  const compact = content.replace(/\s+/gu, "").replace(/-/gu, "+").replace(/_/gu, "/");
  try {
    const binary = atob(compact);
    const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    return unsupportedFormat();
  }
}

function encodedBase64(content: string): string {
  const bytes = new TextEncoder().encode(content);
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 8192) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192));
  }
  return btoa(binary);
}

interface NodeList {
  text: string;
  lines: string[];
  encoded: boolean;
}

function nodeList(content: string): NodeList {
  const format = validateSubscriptionContent(content).format;
  if (format !== "uri-list" && format !== "base64") return unsupportedFormat();
  const text = (format === "base64" ? decodedBase64(content) : content)
    .replace(/\r\n?/gu, "\n")
    .trim();
  const lines = text.split("\n").map((line) => line.trim()).filter(Boolean);
  if (!lines.some(isSupportedNodeUri)
    || lines.some((line) => !isSupportedNodeUri(line) && !line.startsWith("#"))) {
    return unsupportedFormat();
  }
  return { text, lines, encoded: format === "base64" };
}

function nodeBlocks(lines: string[]): string[][] {
  const blocks: string[][] = [];
  let comments: string[] = [];
  for (const line of lines) {
    if (line.startsWith("#")) {
      comments.push(line);
    } else {
      blocks.push([...comments, line]);
      comments = [];
    }
  }
  if (comments.length > 0 && blocks.length > 0) blocks[blocks.length - 1]!.push(...comments);
  return blocks;
}

function interleave(first: string[], second: string[]): string {
  const firstBlocks = nodeBlocks(first);
  const secondBlocks = nodeBlocks(second);
  const lines: string[] = [];
  for (let index = 0; index < Math.max(firstBlocks.length, secondBlocks.length); index += 1) {
    const firstBlock = firstBlocks[index];
    const secondBlock = secondBlocks[index];
    if (firstBlock !== undefined) lines.push(...firstBlock);
    if (secondBlock !== undefined) lines.push(...secondBlock);
  }
  return lines.join("\n");
}

export function validateManagedContentForMerge(content: string): void {
  nodeList(content);
}

export function mergeSubscriptionContent(
  external: string,
  managed: string | null,
  mergeMode: SubscriptionMergeMode = "external_first",
): string {
  if (managed === null || managed.trim() === "") return external;
  const base = nodeList(external);
  const additional = nodeList(managed);
  let combined: string;
  switch (mergeMode) {
    case "managed_first":
      combined = `${additional.text}\n${base.text}`;
      break;
    case "interleave_external_first":
      combined = interleave(base.lines, additional.lines);
      break;
    case "interleave_managed_first":
      combined = interleave(additional.lines, base.lines);
      break;
    default:
      combined = `${base.text}\n${additional.text}`;
  }
  const result = base.encoded ? encodedBase64(combined) : combined;
  validateSubscriptionContent(result);
  return result;
}
