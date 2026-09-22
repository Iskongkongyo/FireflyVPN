import { app } from "./app/bootstrap";
import type { Env } from "./app/env";
import { cleanupExpiredSecurityState } from "./modules/security-maintenance/service";
import { monitorExternalSources } from "./modules/subscription-catalog/service";

let externalSourceMonitor: Promise<unknown> | null = null;

function monitorExternalSourcesOnce(env: Env): Promise<unknown> {
  if (externalSourceMonitor !== null) return externalSourceMonitor;
  externalSourceMonitor = monitorExternalSources(env).finally(() => {
    externalSourceMonitor = null;
  });
  return externalSourceMonitor;
}

const worker: ExportedHandler<Env> = {
  fetch: (request, env, context) => app.fetch(request, env, context),
  scheduled: (_controller, env, context) => {
    context.waitUntil(Promise.all([
      cleanupExpiredSecurityState(env.DB),
      monitorExternalSourcesOnce(env),
    ]));
  },
};

export default worker;
