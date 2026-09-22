import { defineConfig } from "vitest/config";

const configDirectory = (import.meta as ImportMeta & { readonly dirname: string }).dirname;

export default defineConfig({
  root: configDirectory,
  server: {
    fs: {
      allow: [configDirectory],
    },
  },
  test: {
    environment: "node",
    include: ["test/**/*.test.ts"],
    setupFiles: ["test/setup.ts"],
  },
});
