import type { Hono } from "hono";

import type { AppContext } from "../../app/context";
import { success } from "../../foundation/http/response";
import { buildBootstrap } from "./service";

export function registerRuntimeConfigRoutes(app: Hono<AppContext>): void {
  app.get("/api/v2/bootstrap", async (context) => {
    return success(await buildBootstrap(context.env));
  });
}
