import "dotenv/config";

import { serve, type HttpBindings } from "@hono/node-server";
import type { Server } from "node:http";
import { resolve } from "node:path";

import { app } from "../app/bootstrap";
import type { Env } from "../app/env";
import { cleanupExpiredSecurityState } from "../modules/security-maintenance/service";
import { monitorExternalSources } from "../modules/subscription-catalog/service";
import { NodeStaticAssets } from "./assets";
import { applyMigrations, NodeDatabase } from "./database";
import { NodeKvStore } from "./kv";

function requiredEnvironment(name: "ADMIN_ROUTE" | "ADMIN_TOKEN"): string {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} must be set`);
  if (/^(?:change-me|replace-with)/iu.test(value)) {
    throw new Error(`${name} still contains an example value`);
  }
  return value;
}

function integerEnvironment(name: string, fallback: number): number {
  const raw = process.env[name];
  if (raw === undefined || raw === "") return fallback;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < 1 || value > 65_535) {
    throw new Error(`${name} must be an integer between 1 and 65535`);
  }
  return value;
}

function booleanEnvironment(name: string, fallback: boolean): boolean {
  const raw = process.env[name]?.trim().toLowerCase();
  if (!raw) return fallback;
  if (["1", "true", "yes", "on"].includes(raw)) return true;
  if (["0", "false", "no", "off"].includes(raw)) return false;
  throw new Error(`${name} must be true or false`);
}

function requestWithClientIp(request: Request, bindings: HttpBindings, trustProxy: boolean): Request {
  const headers = new Headers(request.headers);
  const forwarded = headers.get("X-Forwarded-For")?.split(",")[0]?.trim();
  const remote = bindings.incoming.socket.remoteAddress?.replace(/^::ffff:/u, "");
  headers.set("CF-Connecting-IP", (trustProxy ? forwarded : remote) || "unknown");
  return new Request(request, { headers });
}

const applicationRoot = resolve(process.env.APP_ROOT?.trim() || process.cwd());
const databasePath = resolve(applicationRoot, process.env.DB_PATH?.trim() || "data/firefly.sqlite");
const migrationsDirectory = resolve(
  applicationRoot,
  process.env.MIGRATIONS_DIR?.trim() || "database/migrations",
);
const publicDirectory = resolve(applicationRoot, process.env.PUBLIC_DIR?.trim() || "public");
const host = process.env.HOST?.trim() || "127.0.0.1";
const port = integerEnvironment("PORT", 3000);
const trustProxy = booleanEnvironment("TRUST_PROXY", false);

const nodeDatabase = new NodeDatabase(databasePath);
const migrations = applyMigrations(nodeDatabase, migrationsDirectory);
const env: Env = {
  DB: nodeDatabase.asDatabase(),
  CONFIG: new NodeKvStore(nodeDatabase.sqlite).asKeyValueStore(),
  ASSETS: new NodeStaticAssets(publicDirectory).asAssetFetcher(),
  ADMIN_TOKEN: requiredEnvironment("ADMIN_TOKEN"),
  ADMIN_ROUTE: requiredEnvironment("ADMIN_ROUTE").replace(/^\/+|\/+$/gu, ""),
  APP_ENV: process.env.APP_ENV?.trim() || "production",
  AI_API_KEY: process.env.AI_API_KEY?.trim() || undefined,
  AI_MODEL: process.env.AI_MODEL?.trim() || undefined,
};

if (!/^[A-Za-z0-9][A-Za-z0-9_-]{7,127}$/u.test(env.ADMIN_ROUTE)) {
  throw new Error("ADMIN_ROUTE must be 8-128 URL-safe characters");
}

const backgroundTasks = new Set<Promise<unknown>>();
let externalSourceMonitorRunning = false;
function runInBackground(promise: Promise<unknown>): void {
  backgroundTasks.add(promise);
  void promise.finally(() => backgroundTasks.delete(promise));
}

const server = serve({
  hostname: host,
  port,
  fetch: (request, bindings) => app.fetch(
    requestWithClientIp(request, bindings as HttpBindings, trustProxy),
    env,
  ),
}, (info) => {
  console.log(JSON.stringify({
    event: "server_started",
    host,
    port: info.port,
    databasePath,
    migrationsApplied: migrations,
  }));
}) as Server;

const maintenanceTimer = setInterval(() => {
  runInBackground(cleanupExpiredSecurityState(env.DB).catch((error) => {
    console.error(JSON.stringify({
      event: "security_maintenance_failed",
      message: error instanceof Error ? error.message : String(error),
    }));
  }));
  if (!externalSourceMonitorRunning) {
    externalSourceMonitorRunning = true;
    runInBackground(monitorExternalSources(env).catch((error) => {
      console.error(JSON.stringify({
        event: "external_source_monitor_failed",
        message: error instanceof Error ? error.message : String(error),
      }));
    }).finally(() => {
      externalSourceMonitorRunning = false;
    }));
  }
}, 60 * 60 * 1_000);
maintenanceTimer.unref();

let shuttingDown = false;
async function shutdown(signal: string): Promise<void> {
  if (shuttingDown) return;
  shuttingDown = true;
  clearInterval(maintenanceTimer);
  console.log(JSON.stringify({ event: "server_stopping", signal }));
  server.close(async () => {
    await Promise.allSettled([...backgroundTasks]);
    nodeDatabase.close();
    process.exit(0);
  });
  setTimeout(() => process.exit(1), 10_000).unref();
}

process.once("SIGINT", () => void shutdown("SIGINT"));
process.once("SIGTERM", () => void shutdown("SIGTERM"));
