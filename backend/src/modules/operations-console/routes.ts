import type { Context, Hono } from "hono";

import type { AppContext } from "../../app/context";
import { writeAdminAudit } from "../../foundation/observability/audit";
import { success } from "../../foundation/http/response";
import { INPUT_LIMITS, readJsonBody } from "../../foundation/security/input-limits";
import {
  createSubscriptionSource,
  getAdminSource,
  getAdminSources,
  inspectAdminSource,
  patchSubscriptionSource,
  removeSubscriptionSource,
} from "../subscription-catalog/service";
import { validateSubscriptionContent } from "../subscription-catalog/validator";
import { AppError } from "../../foundation/http/errors";
import { issueAuthenticatedAdminJwt, requireAdmin, requireAdminRoute, requireAdminToken } from "./auth";
import { readAdminAccess, updateAdminAccess } from "./access-settings";
import { readOrCreateJwtSecret, revokeAdminJwt } from "./jwt";
import { sha256Base64Url } from "../../foundation/crypto/digest";
import { decodeBase64Url } from "../../foundation/crypto/base64url";
import { normalizeRuntimeConfig } from "../runtime-config/schema";
import { readRuntimeConfig, writeRuntimeConfig } from "../runtime-config/store";
import { analytics, dashboard } from "../analytics/service";
import {
  listAdminDevices,
  listAdminDevicesPage,
  deleteAuditLogs,
  listAuditLogsPage,
  setDeviceStatus,
} from "./repository";
import {
  getAdminAccount,
  listAdminAccountsPage,
  setAccountStatus,
} from "./repository";
import { businessPeriodKeys } from "../../foundation/time/clock";

const ADMIN_JSON_LIMIT = INPUT_LIMITS.managedSubscriptionBytes + 16 * 1024;
const ID_PATTERN = /^[a-f0-9]{64}$/u;

function readListQuery(context: Context<AppContext>): {
  page: number;
  pageSize: number;
  query: string;
} {
  const requestedPage = Number(context.req.query("page") ?? 1);
  const requestedPageSize = Number(context.req.query("pageSize") ?? 20);
  const rawQuery = (context.req.query("q") ?? "").trim().slice(0, 128);
  const aliases: Record<string, string> = {
    正常: "active",
    活跃: "active",
    封禁: "banned",
    已封禁: "banned",
    撤销: "revoked",
    已撤销: "revoked",
    注销: "deleted",
    已注销: "deleted",
    安卓: "android",
    苹果: "ios",
  };
  return {
    page: Number.isSafeInteger(requestedPage) ? Math.max(requestedPage, 1) : 1,
    pageSize: Number.isSafeInteger(requestedPageSize)
      ? Math.min(Math.max(requestedPageSize, 5), 100)
      : 20,
    query: aliases[rawQuery] ?? rawQuery,
  };
}

function readAuditQuery(context: Context<AppContext>): {
  page: number;
  pageSize: number;
  filters: { from: string; to: string; action: string; requestId: string; targetId: string };
} {
  const requestedPage = Number(context.req.query("page") ?? 1);
  const requestedPageSize = Number(context.req.query("pageSize") ?? 20);
  const isoValue = (name: string): string => {
    const raw = (context.req.query(name) ?? "").trim();
    if (!raw) return "";
    const date = new Date(raw);
    if (Number.isNaN(date.getTime())) throw new AppError("invalid_request", 400);
    return date.toISOString();
  };
  const from = isoValue("from");
  const to = isoValue("to");
  if (from && to && from > to) throw new AppError("invalid_request", 400);
  return {
    page: Number.isSafeInteger(requestedPage) ? Math.max(requestedPage, 1) : 1,
    pageSize: Number.isSafeInteger(requestedPageSize)
      ? Math.min(Math.max(requestedPageSize, 5), 100)
      : 20,
    filters: {
      from,
      to,
      action: (context.req.query("action") ?? "").trim().slice(0, 64),
      requestId: (context.req.query("requestId") ?? "").trim().slice(0, 128),
      targetId: (context.req.query("targetId") ?? "").trim().slice(0, 128),
    },
  };
}

function auditDate(value: unknown): string {
  if (typeof value !== "string" || !value.trim()) return "";
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) throw new AppError("invalid_request", 400);
  return parsed.toISOString();
}

async function safeAdminDevices(devices: Array<Record<string, unknown>>): Promise<Array<Record<string, unknown>>> {
  return Promise.all(devices.map(async (device) => {
    const { publicKeySpki, ...safe } = device;
    const fingerprint = typeof publicKeySpki === "string"
      ? (await sha256Base64Url(decodeBase64Url(publicKeySpki))).slice(0, 16)
      : "";
    return { ...safe, publicKeyFingerprint: fingerprint };
  }));
}

export function registerOperationsRoutes(app: Hono<AppContext>): void {
  app.post("/:adminRoute/api/session/login", async (context) => {
    await requireAdminToken(context.req.raw, context.env, context.req.param("adminRoute"));
    const session = await issueAuthenticatedAdminJwt(context.env);
    await writeAdminAudit(context.env, context.req.raw, {
      action: "admin.login",
      targetType: "admin-session",
      requestId: context.get("requestId"),
    });
    return success(session);
  });

  app.post("/:adminRoute/api/session/logout", async (context) => {
    const session = await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
    await revokeAdminJwt(context.env.DB, session);
    await writeAdminAudit(context.env, context.req.raw, {
      action: "admin.logout",
      targetType: "admin-session",
      requestId: context.get("requestId"),
    });
    return success({ recorded: true });
  });

  app.get("/:adminRoute/api/dashboard", async (context) => {
    await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
    return success(await dashboard(context.env.DB));
  });

  app.get("/:adminRoute/api/analytics", async (context) => {
    await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
    const requestedDays = Number(context.req.query("days") ?? 30);
    const requestedMonths = Number(context.req.query("months") ?? 12);
    const days = Number.isSafeInteger(requestedDays) ? Math.min(Math.max(requestedDays, 1), 90) : 30;
    const months = Number.isSafeInteger(requestedMonths) ? Math.min(Math.max(requestedMonths, 1), 24) : 12;
    return success(await analytics(context.env.DB, days, months));
  });

  app.get("/:adminRoute/api/subscriptions", async (context) => {
    await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
    return success(await getAdminSources(context.env));
  });

  app.get("/:adminRoute/api/subscriptions/:id", async (context) => {
    await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
    return success(await getAdminSource(context.env, context.req.param("id")));
  });

  app.get("/:adminRoute/api/accounts", async (context) => {
    await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
    const { day, month } = businessPeriodKeys(new Date());
    const { page, pageSize, query } = readListQuery(context);
    return success(await listAdminAccountsPage(
      context.env.DB,
      day,
      month,
      page,
      pageSize,
      query,
    ));
  });

  app.get("/:adminRoute/api/accounts/:id", async (context) => {
    await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
    const accountId = context.req.param("id");
    if (!ID_PATTERN.test(accountId)) throw new AppError("not_found", 404);
    const { day, month } = businessPeriodKeys(new Date());
    const account = await getAdminAccount(context.env.DB, accountId, day, month);
    if (account === null) throw new AppError("not_found", 404);
    const devices = await safeAdminDevices(await listAdminDevices(context.env.DB, accountId));
    return success({ ...account, devices });
  });

  for (const [action, accountStatus] of [
    ["ban", "banned"],
    ["unban", "active"],
  ] as const) {
    app.post(`/:adminRoute/api/accounts/:id/${action}`, async (context) => {
      await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
      const accountId = context.req.param("id");
      if (!ID_PATTERN.test(accountId)) throw new AppError("not_found", 404);
      if (!await setAccountStatus(context.env.DB, accountId, accountStatus)) {
        throw new AppError("not_found", 404);
      }
      await writeAdminAudit(context.env, context.req.raw, {
        action: `account.${action}`,
        targetType: "account",
        targetId: accountId,
        requestId: context.get("requestId"),
        detail: { status: accountStatus },
      });
      return success({ accountId, status: accountStatus });
    });
  }

  app.post("/:adminRoute/api/subscriptions", async (context) => {
    await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
    const source = await createSubscriptionSource(
      context.env,
      await readJsonBody(context.req.raw, ADMIN_JSON_LIMIT),
    );
    await writeAdminAudit(context.env, context.req.raw, {
      action: "subscription.create",
      targetType: "subscription",
      targetId: source.id,
      requestId: context.get("requestId"),
      detail: { sourceType: source.sourceType, enabled: source.enabled === 1 },
    });
    return success(source, 201);
  });

  app.patch("/:adminRoute/api/subscriptions/:id", async (context) => {
    await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
    const source = await patchSubscriptionSource(
      context.env,
      context.req.param("id"),
      await readJsonBody(context.req.raw, ADMIN_JSON_LIMIT),
    );
    await writeAdminAudit(context.env, context.req.raw, {
      action: "subscription.update",
      targetType: "subscription",
      targetId: source.id,
      requestId: context.get("requestId"),
      detail: { sourceType: source.sourceType, enabled: source.enabled === 1 },
    });
    return success(source);
  });

  app.delete("/:adminRoute/api/subscriptions/:id", async (context) => {
    await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
    const sourceId = context.req.param("id");
    await removeSubscriptionSource(context.env, sourceId);
    await writeAdminAudit(context.env, context.req.raw, {
      action: "subscription.delete",
      targetType: "subscription",
      targetId: sourceId,
      requestId: context.get("requestId"),
    });
    return success({ deleted: true });
  });

  app.post("/:adminRoute/api/subscriptions/:id/test", async (context) => {
    await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
    const { source, content } = await inspectAdminSource(context.env, context.req.param("id"));
    return success({ id: source.id, ...validateSubscriptionContent(content) });
  });

  app.post("/:adminRoute/api/subscriptions/:id/refresh", async (context) => {
    await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
    const { source, content } = await inspectAdminSource(
      context.env,
      context.req.param("id"),
      true,
    );
    if (source.sourceType !== "external") throw new AppError("invalid_request", 400);
    const summary = validateSubscriptionContent(content);
    await writeAdminAudit(context.env, context.req.raw, {
      action: "subscription.refresh",
      targetType: "subscription",
      targetId: source.id,
      requestId: context.get("requestId"),
      detail: {
        bytes: summary.bytes,
        lines: summary.lines,
        format: summary.format,
      },
    });
    return success({ id: source.id, ...summary });
  });

  app.get("/:adminRoute/api/devices", async (context) => {
    await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
    const { page, pageSize, query } = readListQuery(context);
    const result = await listAdminDevicesPage(context.env.DB, page, pageSize, query);
    return success({ ...result, items: await safeAdminDevices(result.items) });
  });

  for (const [action, status] of [
    ["ban", "banned"],
    ["unban", "active"],
    ["revoke", "revoked"],
  ] as const) {
    app.post(`/:adminRoute/api/devices/:id/${action}`, async (context) => {
      await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
      const deviceId = context.req.param("id");
      if (!ID_PATTERN.test(deviceId)) throw new AppError("invalid_device_id", 400);
      if (!await setDeviceStatus(context.env.DB, deviceId, status)) {
        throw new AppError("not_found", 404);
      }
      await writeAdminAudit(context.env, context.req.raw, {
        action: `device.${action}`,
        targetType: "device",
        targetId: deviceId,
        requestId: context.get("requestId"),
        detail: { status },
      });
      return success({ deviceId, status });
    });
  }

  app.get("/:adminRoute/api/settings", async (context) => {
    await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
    const [runtime, access, jwtSecret] = await Promise.all([
      readRuntimeConfig(context.env),
      readAdminAccess(context.env),
      readOrCreateJwtSecret(context.env.DB),
    ]);
    return success({
      ...runtime,
      adminAccess: {
        accessPath: access.accessPath,
        adminTokenConfigured: access.tokenConfiguredInKv,
        jwtSecretConfigured: true,
        jwtSecretCustomized: jwtSecret.customized,
      },
    });
  });

  app.put("/:adminRoute/api/settings", async (context) => {
    await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
    const settings = normalizeRuntimeConfig(await readJsonBody(context.req.raw));
    await writeRuntimeConfig(context.env, settings);
    await writeAdminAudit(context.env, context.req.raw, {
      action: "settings.update",
      targetType: "runtime-config",
      requestId: context.get("requestId"),
      detail: {
        noticeEnabled: settings.notice.enabled,
        appUpdateEnabled: settings.appUpdate !== null,
        pcAppUpdateEnabled: settings.pcAppUpdate !== null,
      },
    });
    return success(settings);
  });

  app.put("/:adminRoute/api/settings/admin-access", async (context) => {
    await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
    const result = await updateAdminAccess(
      context.env,
      await readJsonBody(context.req.raw),
    );
    await writeAdminAudit(context.env, context.req.raw, {
      action: "admin.access.update",
      targetType: "admin-access",
      requestId: context.get("requestId"),
      detail: {
        accessPath: result.accessPath,
        tokenChanged: result.tokenChanged,
        jwtSecretChanged: result.jwtSecretChanged,
      },
    });
    return success(result);
  });

  app.get("/:adminRoute/api/audit", async (context) => {
    await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
    const { page, pageSize, filters } = readAuditQuery(context);
    return success(await listAuditLogsPage(context.env.DB, page, pageSize, filters));
  });

  app.delete("/:adminRoute/api/audit", async (context) => {
    await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
    const input = await readJsonBody(context.req.raw) as Record<string, unknown>;
    const all = input.all === true;
    const from = auditDate(input.from);
    const to = auditDate(input.to);
    if (!all && !from && !to) throw new AppError("invalid_request", 400);
    if (from && to && from > to) throw new AppError("invalid_request", 400);
    const deleted = await deleteAuditLogs(context.env.DB, from, to, all);
    await writeAdminAudit(context.env, context.req.raw, {
      action: "audit.cleanup",
      targetType: "audit-log",
      requestId: context.get("requestId"),
      detail: { all, from: from || null, to: to || null, deleted },
    });
    return success({ deleted });
  });

  app.post("/:adminRoute/api/ai/insights", async (context) => {
    await requireAdmin(context.req.raw, context.env, context.req.param("adminRoute"));
    throw new AppError("feature_disabled", 501);
  });

  app.get("/:adminRoute/assets/:file", async (context) => {
    await requireAdminRoute(context.env, context.req.param("adminRoute"));
    const file = context.req.param("file");
    if (file !== "console.css" && file !== "console.js" && file !== "firefly.jpg" && file !== "background.jpg") {
      throw new AppError("not_found", 404);
    }
    const url = new URL(context.req.url);
    url.pathname = `/console/${file}`;
    return context.env.ASSETS.fetch(new Request(url, { headers: context.req.raw.headers }));
  });

  app.get("/:adminRoute", async (context) => {
    await requireAdminRoute(context.env, context.req.param("adminRoute"));
    return context.redirect(`/${context.req.param("adminRoute")}/`, 308);
  });

  app.get("/:adminRoute/", async (context) => {
    await requireAdminRoute(context.env, context.req.param("adminRoute"));
    const url = new URL(context.req.url);
    url.pathname = "/console/index.html";
    const response = await context.env.ASSETS.fetch(new Request(url, {
      headers: context.req.raw.headers,
    }));
    const headers = new Headers(response.headers);
    headers.set("Cache-Control", "no-store");
    return new Response(response.body, { status: response.status, headers });
  });
}
