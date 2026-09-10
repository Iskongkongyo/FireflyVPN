import type { Env } from "../../app/env";

export function configurationStore(env: Env): KVNamespace {
  return env.CONFIG;
}
