import type { Hono } from "hono";

import type { AppContext } from "../../app/context";
import { writeAdminAudit } from "../../foundation/observability/audit";
import { success } from "../../foundation/http/response";
import { authenticateDevice } from "../secure-delivery/authenticator";
import { getAnonymousAccount, removeAnonymousAccount } from "./service";

export function registerAnonymousAccountRoutes(app: Hono<AppContext>): void {
  app.get("/api/v2/accounts/me", async (context) => {
    const authenticated = await authenticateDevice(context.req.raw, context.env);
    return success(await getAnonymousAccount(context.env.DB, authenticated));
  });

  app.delete("/api/v2/accounts/me", async (context) => {
    const authenticated = await authenticateDevice(context.req.raw, context.env);
    const result = await removeAnonymousAccount(context.env.DB, authenticated);
    await writeAdminAudit(context.env, context.req.raw, {
      action: "account.self_delete",
      targetType: "account",
      targetId: authenticated.accountId,
      requestId: context.get("requestId"),
      detail: { revokedDevices: result.revokedDevices },
    });
    return success(result);
  });
}
