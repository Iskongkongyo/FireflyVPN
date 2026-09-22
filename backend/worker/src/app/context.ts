import type { Env } from "./env";

export type AppContext = {
  Bindings: Env;
  Variables: {
    requestId: string;
  };
};
