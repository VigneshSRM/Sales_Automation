import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest, buildQuery } from "../api";

function mockFetch(response: { ok: boolean; status: number; body: string }) {
  return vi.fn().mockResolvedValue({
    ok: response.ok,
    status: response.status,
    text: async () => response.body,
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("buildQuery", () => {
  it("omits empty values and encodes the rest", () => {
    expect(buildQuery({ page: 0, q: "", status: undefined, term: "a b" })).toBe("?page=0&term=a+b");
  });

  it("returns an empty string when nothing is set", () => {
    expect(buildQuery({ a: null, b: undefined })).toBe("");
  });
});

describe("apiRequest", () => {
  it("unwraps the success envelope", async () => {
    vi.stubGlobal("fetch", mockFetch({
      ok: true,
      status: 200,
      body: JSON.stringify({ success: true, data: { items: [], page: 0 } }),
    }));
    await expect(apiRequest("/api/v1/leads")).resolves.toEqual({ items: [], page: 0 });
  });

  it("surfaces server field errors verbatim", async () => {
    vi.stubGlobal("fetch", mockFetch({
      ok: false,
      status: 422,
      body: JSON.stringify({
        success: false,
        error: {
          code: "VALIDATION_FAILED",
          message: "Validation failed",
          fieldErrors: [{ field: "email", code: "INVALID", message: "Not a valid address" }],
          requestId: "req-1",
        },
      }),
    }));

    const error = (await apiRequest("/api/v1/leads").catch((thrown) => thrown)) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(422);
    expect(error.code).toBe("VALIDATION_FAILED");
    expect(error.fieldError("email")).toBe("Not a valid address");
    expect(error.requestId).toBe("req-1");
  });

  it("maps an expired session to UNAUTHENTICATED", async () => {
    vi.stubGlobal("fetch", mockFetch({ ok: false, status: 401, body: "" }));
    const error = (await apiRequest("/api/v1/auth/me").catch((thrown) => thrown)) as ApiError;
    expect(error.code).toBe("UNAUTHENTICATED");
  });

  it("reports an unreachable server without leaking internals", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("ECONNREFUSED 127.0.0.1")));
    const error = (await apiRequest("/api/v1/leads").catch((thrown) => thrown)) as ApiError;
    expect(error.code).toBe("NETWORK_UNAVAILABLE");
    expect(error.message).not.toContain("ECONNREFUSED");
  });

  it("sends the CSRF header on unsafe methods only", async () => {
    const fetchMock = mockFetch({ ok: true, status: 200, body: JSON.stringify({ success: true, data: {} }) });
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("document", { cookie: "XSRF-TOKEN=abc123" });

    await apiRequest("/api/v1/leads", { method: "POST", body: { name: "x" } });
    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers["X-XSRF-TOKEN"]).toBe("abc123");

    fetchMock.mockClear();
    await apiRequest("/api/v1/leads");
    expect(fetchMock.mock.calls[0][1].headers["X-XSRF-TOKEN"]).toBeUndefined();
  });
});
