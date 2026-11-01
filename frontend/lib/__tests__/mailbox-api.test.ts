import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "../api";

/**
 * Contract tests for the two-way mailbox client.
 *
 * These pin the exact HTTP verb and path for each inbox control so a backend route rename cannot
 * silently leave a button that no longer works (the failure mode the delivery brief forbids).
 */

type Captured = { url: string; init: RequestInit };

function stubFetch(data: unknown): Captured[] {
  const calls: Captured[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      return {
        ok: true,
        status: 200,
        text: async () => JSON.stringify({ success: true, data, meta: { requestId: "test" } }),
      } as unknown as Response;
    }),
  );
  return calls;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("mailbox API contract", () => {
  it("lists mailboxes from the accounts collection", async () => {
    const calls = stubFetch([]);
    await api.mailboxAccounts();
    expect(calls[0].url).toBe("/api/v1/email/accounts");
    expect(calls[0].init.method ?? "GET").toBe("GET");
  });

  it("posts a connection test to the mailbox test endpoint", async () => {
    const calls = stubFetch({ ok: true, detail: "connected" });
    await api.testMailbox("acc-1");
    expect(calls[0].url).toBe("/api/v1/email/accounts/acc-1/test");
    expect(calls[0].init.method).toBe("POST");
  });

  it("sends the enable flag to the status endpoint", async () => {
    const calls = stubFetch({ id: "acc-1", status: "ACTIVE" });
    await api.setMailboxStatus("acc-1", true);
    expect(calls[0].url).toBe("/api/v1/email/accounts/acc-1/status");
    expect(JSON.parse(String(calls[0].init.body))).toEqual({ enabled: true });
  });

  it("queries the inbox with only the filters that are set", async () => {
    const calls = stubFetch({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0, sort: "", appliedFilters: [] });
    await api.inbox({ status: "OPEN", unreadOnly: true, q: undefined, unassigned: false });
    expect(calls[0].url).toBe("/api/v1/email/inbox?status=OPEN&unreadOnly=true&unassigned=false");
  });

  it("routes reply, analysis and approval decisions to their own endpoints", async () => {
    const calls = stubFetch({});
    await api.replyConversation("conv-1", { body: "Thanks", sendImmediately: false });
    await api.analyzeMessage("msg-1");
    await api.approveDraft("appr-1");
    await api.rejectDraft("appr-2", "tone is wrong");

    expect(calls.map((call) => `${call.init.method} ${call.url}`)).toEqual([
      "POST /api/v1/email/conversations/conv-1/reply",
      "POST /api/v1/email/messages/msg-1/analyze",
      "POST /api/v1/email/approvals/appr-1/approve",
      "POST /api/v1/email/approvals/appr-2/reject",
    ]);
    expect(JSON.parse(String(calls[3].init.body))).toEqual({ reason: "tone is wrong" });
  });

  it("toggles a response-policy rule through the enabled endpoint", async () => {
    const calls = stubFetch({ id: "rule-1", enabled: false });
    await api.setMailboxRuleEnabled("rule-1", false);
    expect(calls[0].url).toBe("/api/v1/email/rules/rule-1/enabled");
    expect(JSON.parse(String(calls[0].init.body))).toEqual({ enabled: false });
  });
});
