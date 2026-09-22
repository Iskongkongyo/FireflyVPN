import type { Hono } from "hono";

import type { AppContext } from "../../app/context";
import { success } from "../../foundation/http/response";
import { readJsonBody } from "../../foundation/security/input-limits";
import { writeAdminAudit } from "../../foundation/observability/audit";
import { authenticateDevice } from "../secure-delivery/authenticator";
import {
  enrollDevice,
  getAccountDevices,
  revokeOwnedDevice,
  rotateDeviceKey,
} from "./service";

export function registerDeviceRegistryRoutes(app: Hono<AppContext>): void {
  app.post("/api/v2/devices/enroll", async (context) => {
    const result = await enrollDevice(
      context.req.raw,
      context.env.DB,
      await readJsonBody(context.req.raw),
      context.env,
    );
    return success(result, result.alreadyEnrolled ? 200 : 201);
  });

  app.get("/api/v2/devices", async (context) => {
    const device = await authenticateDevice(context.req.raw, context.env);
    return success(await getAccountDevices(context.env.DB, device.accountId));
  });

  app.post("/api/v2/devices/:deviceId/revoke", async (context) => {
    const device = await authenticateDevice(context.req.raw, context.env);
    const targetDeviceId = context.req.param("deviceId");
    await revokeOwnedDevice(context.env.DB, device, targetDeviceId);
    await writeAdminAudit(context.env, context.req.raw, {
      action: "device.self_revoke",
      targetType: "device",
      targetId: targetDeviceId,
      requestId: context.get("requestId"),
      detail: { accountId: device.accountId },
    });
    return success({ deviceId: targetDeviceId, status: "revoked" });
  });

  app.post("/api/v2/devices/:deviceId/rotate-key", async (context) => {
    const request = context.req.raw;
    const device = await authenticateDevice(request, context.env);
    const result = await rotateDeviceKey(
      request,
      context.env.DB,
      device,
      context.req.param("deviceId"),
      await readJsonBody(request),
      context.env,
    );
    await writeAdminAudit(context.env, request, {
      action: "device.rotate_key",
      targetType: "device",
      targetId: device.deviceId,
      requestId: context.get("requestId"),
      detail: { accountId: device.accountId, cryptoVersion: result.cryptoVersion },
    });
    return success(result);
  });
}
