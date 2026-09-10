import { app } from "./app/bootstrap";
import type { Env } from "./app/env";
import { cleanupExpiredSecurityState } from "./modules/security-maintenance/service";

const worker: ExportedHandler<Env> = {
  fetch: (request, env, context) => app.fetch(request, env, context),
  scheduled: (_controller, env, context) => {
    context.waitUntil(cleanupExpiredSecurityState(env.DB));
  },
};

export default worker;
