import { afterEach, describe, expect, it, vi } from "vitest";

import { fetchExternalSource, validateExternalSourceUrl } from "../../src/modules/subscription-catalog/source-fetcher";

describe("external subscription fetcher", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it.each([
    "http://example.com/sub",
    "file:///tmp/subscription",
    "https://localhost/sub",
    "https://127.0.0.1/sub",
    "https://[::1]/sub",
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

  it("validates each redirect before making the next request", async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(null, {
        status: 302,
        headers: { Location: "/next" },
      }))
      .mockResolvedValueOnce(new Response("vless://id@example.com:443#Firefly"));

    await expect(fetchExternalSource("https://example.com/sub", 1_000, fetcher))
      .resolves.toContain("vless://");
    expect(fetcher).toHaveBeenNthCalledWith(1, new URL("https://example.com/sub"),
      expect.objectContaining({ redirect: "manual" }));
    expect(fetcher).toHaveBeenNthCalledWith(2, new URL("https://example.com/next"),
      expect.objectContaining({ redirect: "manual" }));
  });

  it.each([
    "http://example.com/sub",
    "https://127.0.0.1/sub",
    "https://[::1]/sub",
  ])("does not request an unsafe redirect target %s", async (location) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, {
      status: 302,
      headers: { Location: location },
    }));

    await expect(fetchExternalSource("https://example.com/sub", 1_000, fetcher))
      .rejects.toMatchObject({ code: "invalid_request" });
    expect(fetcher).toHaveBeenCalledOnce();
  });

  it("stops after five redirects", async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => new Response(null, {
      status: 302,
      headers: { Location: "/next" },
    }));

    await expect(fetchExternalSource("https://example.com/sub", 1_000, fetcher))
      .rejects.toMatchObject({ code: "upstream_failed", status: 502 });
    expect(fetcher).toHaveBeenCalledTimes(6);
  });

  it("retries transient network failures twice before succeeding", async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn<typeof fetch>()
      .mockRejectedValueOnce(new TypeError("temporary network failure"))
      .mockRejectedValueOnce(new DOMException("timed out", "AbortError"))
      .mockResolvedValueOnce(new Response("vless://id@example.com:443#Firefly"));

    const assertion = expect(fetchExternalSource("https://example.com/sub", 1_000, fetcher))
      .resolves.toContain("vless://");
    await vi.runAllTimersAsync();
    await assertion;
    expect(fetcher).toHaveBeenCalledTimes(3);
  });

  it.each([408, 429, 503])("retries HTTP %i responses", async (status) => {
    vi.useFakeTimers();
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response("temporary failure", { status }))
      .mockResolvedValueOnce(new Response("vless://id@example.com:443#Firefly"));

    const assertion = expect(fetchExternalSource("https://example.com/sub", 1_000, fetcher))
      .resolves.toContain("vless://");
    await vi.runAllTimersAsync();
    await assertion;
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it("does not retry a deterministic HTTP 4xx response", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response("not found", { status: 404 }));

    await expect(fetchExternalSource("https://example.com/sub", 1_000, fetcher))
      .rejects.toMatchObject({ code: "upstream_failed", status: 502 });
    expect(fetcher).toHaveBeenCalledOnce();
  });

  it("rejects upstream errors and HTML bodies", async () => {
    vi.useFakeTimers();
    const failed = vi.fn<typeof fetch>().mockResolvedValue(new Response("failed", { status: 502 }));
    const failedAssertion = expect(fetchExternalSource("https://example.com/sub", 1_000, failed))
      .rejects.toMatchObject({ code: "upstream_failed", status: 502 });
    await vi.runAllTimersAsync();
    await failedAssertion;
    expect(failed).toHaveBeenCalledTimes(3);

    const html = vi.fn<typeof fetch>().mockResolvedValue(new Response("<html>error</html>", {
      status: 200,
      headers: { "Content-Type": "text/html" },
    }));
    await expect(fetchExternalSource("https://example.com/sub", 1_000, html))
      .rejects.toMatchObject({ code: "subscription_unavailable" });
    expect(html).toHaveBeenCalledOnce();
  });
});
