import { describe, expect, it, vi } from "vitest";

import { fetchExternalSource, validateExternalSourceUrl } from "../../src/modules/subscription-catalog/source-fetcher";

describe("external subscription fetcher", () => {
  it.each([
    "http://example.com/sub",
    "file:///tmp/subscription",
    "https://localhost/sub",
    "https://127.0.0.1/sub",
    "https://192.168.1.1/sub",
    "https://user:password@example.com/sub",
  ])("rejects unsafe URL %s", (url) => {
    expect(() => validateExternalSourceUrl(url)).toThrow();
  });

  it("accepts HTTPS and validates fetched content", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(
      "vless://id@example.com:443#Firefly",
      { status: 200, headers: { "Content-Type": "text/plain" } },
    ));

    await expect(fetchExternalSource("https://example.com/sub", 1_000, fetcher))
      .resolves.toContain("vless://");
    expect(fetcher).toHaveBeenCalledOnce();
  });

  it("rejects upstream errors and HTML bodies", async () => {
    const failed = vi.fn<typeof fetch>().mockResolvedValue(new Response("failed", { status: 502 }));
    await expect(fetchExternalSource("https://example.com/sub", 1_000, failed))
      .rejects.toMatchObject({ code: "upstream_failed", status: 502 });

    const html = vi.fn<typeof fetch>().mockResolvedValue(new Response("<html>error</html>", {
      status: 200,
      headers: { "Content-Type": "text/html" },
    }));
    await expect(fetchExternalSource("https://example.com/sub", 1_000, html))
      .rejects.toMatchObject({ code: "subscription_unavailable" });
  });
});
