import { readFile } from "node:fs/promises";
import { extname, relative, resolve, sep } from "node:path";

const CONTENT_TYPES: Record<string, string> = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".webp": "image/webp",
};

export class NodeStaticAssets {
  private readonly root: string;

  constructor(root: string) {
    this.root = resolve(root);
  }

  async fetch(input: RequestInfo | URL): Promise<Response> {
    const request = input instanceof Request ? input : new Request(input);
    const pathname = decodeURIComponent(new URL(request.url).pathname);
    const filename = resolve(this.root, `.${pathname}`);
    const relativePath = relative(this.root, filename);
    if (relativePath.startsWith(`..${sep}`) || relativePath === ".." || relativePath.includes("\0")) {
      return new Response("Not found", { status: 404 });
    }
    try {
      const body = await readFile(filename);
      return new Response(body, {
        headers: {
          "Content-Type": CONTENT_TYPES[extname(filename).toLowerCase()] ?? "application/octet-stream",
        },
      });
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") {
        return new Response("Not found", { status: 404 });
      }
      throw error;
    }
  }

  asAssetFetcher(): AssetFetcher {
    return this as unknown as AssetFetcher;
  }
}
