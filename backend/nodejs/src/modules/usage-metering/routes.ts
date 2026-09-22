import type { Hono } from "hono";

import type { AppContext } from "../../app/context";
import { success } from "../../foundation/http/response";
import { readJsonBody } from "../../foundation/security/input-limits";
import { authenticateDevice } from "../secure-delivery/authenticator";
import { reportUsage } from "./service";

export function registerUsageMeteringRoutes(app: Hono<AppContext>): void {
  app.post("/api/v2/usage/report", async (context) => {
    const device = await authenticateDevice(context.req.raw, context.env);
    const result = await reportUsage(
      context.env.DB,
      device,
      await readJsonBody(context.req.raw),
    );
    return success(result);
  });
}
