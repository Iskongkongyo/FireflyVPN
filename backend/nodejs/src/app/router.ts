import { Hono } from "hono";

import type { AppContext } from "./context";
import { apiCors } from "../foundation/http/cors";
import { toAppError } from "../foundation/http/errors";
import { requestIdMiddleware } from "../foundation/http/request-id";
import { failure, success } from "../foundation/http/response";
import { registerDeviceRegistryRoutes } from "../modules/device-registry/routes";
import { registerOperationsRoutes } from "../modules/operations-console/routes";
import { registerRuntimeConfigRoutes } from "../modules/runtime-config/routes";
import { registerSecureDeliveryRoutes } from "../modules/secure-delivery/routes";
import { registerUsageMeteringRoutes } from "../modules/usage-metering/routes";
import { registerAnonymousAccountRoutes } from "../modules/anonymous-account/routes";

export function createApp(): Hono<AppContext> {
  const app = new Hono<AppContext>();

  app.use("*", requestIdMiddleware());
  app.use("*", apiCors);

  app.get("/health", () => success({ status: "ok" }));
  registerRuntimeConfigRoutes(app);
  registerDeviceRegistryRoutes(app);
  registerAnonymousAccountRoutes(app);
  registerSecureDeliveryRoutes(app);
  registerUsageMeteringRoutes(app);
  registerOperationsRoutes(app);

  app.notFound((context) => failure(
    "not_found",
    context.get("requestId"),
    404,
  ));

  app.onError((error, context) => {
    const appError = toAppError(error);
    console.error(JSON.stringify({
      event: "request_failed",
      requestId: context.get("requestId"),
      method: context.req.method,
      path: context.req.path,
      status: appError.status,
    }));
    return failure(appError.code, context.get("requestId"), appError.status);
  });

  return app;
}
