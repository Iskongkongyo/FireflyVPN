import type { Env } from "../../app/env";
import { sha256Base64Url } from "../crypto/digest";

export interface AuditEvent {
  action: string;
  targetType?: string;
  targetId?: string;
  requestId: string;
  detail?: Record<string, string | number | boolean | null>;
}

export async function writeAdminAudit(
  env: Env,
  request: Request,
  event: AuditEvent,
): Promise<void> {
  const ip = request.headers.get("CF-Connecting-IP")?.trim() || "unknown";
  const ipHash = await sha256Base64Url(ip);
  await env.DB.prepare(`
    INSERT INTO admin_audit_logs (
      action, target_type, target_id, request_id, ip_hash, ip_address, detail_json, created_at
    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
  `).bind(
    event.action,
    event.targetType ?? null,
    event.targetId ?? null,
    event.requestId,
    ipHash,
    ip,
    event.detail === undefined ? null : JSON.stringify(event.detail),
    new Date().toISOString(),
  ).run();
}
