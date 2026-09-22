import { resolve } from "node:path";

import { importCloudflareBackup } from "../src/node/cloudflare-import";

interface Arguments {
  d1SqlPath: string;
  kvKeyListPath: string;
  kvValuesPath: string;
  outputPath: string;
  migrationsDirectory: string;
  dryRun: boolean;
}

const HELP = `
Import a Cloudflare D1 + KV export into a new Firefly SQLite database.

Usage:
  npm run migrate:cloudflare -- \\
    --d1 ./backups/d1-backup.sql \\
    --kv-list ./backups/kv-key-list.json \\
    --kv-values ./backups/kv-values.json \\
    --out ./data/firefly-imported.sqlite

Options:
  --d1 <path>          Full D1 SQL export
  --kv-list <path>     Output of "wrangler kv key list"
  --kv-values <path>   Output of "wrangler kv bulk get"
  --out <path>         New SQLite database (must not already exist)
  --migrations <path>  Migration directory (default: database/migrations)
  --dry-run            Import and validate, then discard the generated database
  --help               Show this help
`;

function parseArguments(values: string[]): Arguments {
  const options = new Map<string, string>();
  let dryRun = false;
  for (let index = 0; index < values.length; index += 1) {
    const argument = values[index]!;
    if (argument === "--help") {
      console.log(HELP.trim());
      process.exit(0);
    }
    if (argument === "--dry-run") {
      dryRun = true;
      continue;
    }
    if (!["--d1", "--kv-list", "--kv-values", "--out", "--migrations"].includes(argument)) {
      throw new Error(`Unknown argument: ${argument}`);
    }
    const value = values[index + 1];
    if (!value || value.startsWith("--")) throw new Error(`Missing value for ${argument}`);
    options.set(argument, value);
    index += 1;
  }
  const required = (name: string): string => {
    const value = options.get(name);
    if (!value) throw new Error(`Missing required argument: ${name}`);
    return resolve(value);
  };
  return {
    d1SqlPath: required("--d1"),
    kvKeyListPath: required("--kv-list"),
    kvValuesPath: required("--kv-values"),
    outputPath: required("--out"),
    migrationsDirectory: resolve(options.get("--migrations") ?? "database/migrations"),
    dryRun,
  };
}

try {
  const report = await importCloudflareBackup(parseArguments(process.argv.slice(2)));
  console.log(JSON.stringify({ event: "cloudflare_import_complete", ...report }, null, 2));
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
}
