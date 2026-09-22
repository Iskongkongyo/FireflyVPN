import type { MiddlewareHandler } from "hono";

import type { AppContext } from "../../app/context";

const SAFE_REQUEST_ID = /^[A-Za-z0-9._:-]{1,128}$/;

export function requestIdMiddleware(): MiddlewareHandler<AppContext> {
  return async (context, next) => {
    const supplied = context.req.header("X-Request-ID")?.trim();
    const requestId = supplied && SAFE_REQUEST_ID.test(supplied)
      ? supplied
      : crypto.randomUUID();

    context.set("requestId", requestId);
    await next();
    context.res.headers.set("X-Request-ID", requestId);
  };
}
