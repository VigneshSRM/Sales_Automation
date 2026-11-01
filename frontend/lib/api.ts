import type { ApiEnvelope, ApiErrorBody, PageResponse } from "./types";

/**
 * Single HTTP client for the Spring Boot API.
 *
 * - Relative URLs only: the UI is served same-origin from the WAR (ADR-004).
 * - Session cookie is HttpOnly; the CSRF cookie (XSRF-TOKEN) is echoed in X-XSRF-TOKEN
 *   for mutating requests, as required by the backend's double-submit CSRF policy.
 * - Server error envelopes are surfaced verbatim so the UI can show stable codes and
 *   per-field validation messages instead of inventing its own rules.
 */

export class ApiError extends Error {
  readonly code: string;
  readonly status: number;
  readonly fieldErrors: { field: string; code: string; message: string }[];
  readonly requestId?: string;

  constructor(status: number, body: ApiErrorBody) {
    super(body.message || "Request failed");
    this.name = "ApiError";
    this.status = status;
    this.code = body.code;
    this.fieldErrors = body.fieldErrors ?? [];
    this.requestId = body.requestId ?? body.requestId;
  }

  fieldError(field: string): string | undefined {
    return this.fieldErrors.find((error) => error.field === field)?.message;
  }
}

function readCookie(name: string): string | null {
  if (typeof document === "undefined") return null;
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : null;
}

type RequestOptions = {
  method?: "GET" | "POST" | "PATCH" | "DELETE";
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
};

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? "GET";

  const headers: Record<string, string> = {
    Accept: "application/json",
    ...(options.body !== undefined ? { "Content-Type": "application/json" } : {}),
    ...(method !== "GET" ? { "X-XSRF-TOKEN": readCookie("XSRF-TOKEN") ?? "" } : {}),
    ...options.headers,
  };

  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers,
      credentials: "same-origin",
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    });
  } catch {
    throw new ApiError(0, {
      code: "NETWORK_UNAVAILABLE",
      message: "The server could not be reached. Check your connection and try again.",
    });
  }

  const text = await response.text();
  let envelope: ApiEnvelope<T> | null = null;
  if (text) {
    try {
      envelope = JSON.parse(text) as ApiEnvelope<T>;
    } catch {
      envelope = null;
    }
  }
  if (!response.ok) {
    throw new ApiError(
      response.status,
      envelope?.error ?? {
        code: response.status === 401 ? "UNAUTHENTICATED" : "REQUEST_FAILED",
        message:
          response.status === 401
            ? "Your session has expired. Sign in again."
            : "The request could not be completed.",
      },
    );
  }
  if (!envelope) {
    throw new ApiError(response.status, { code: "INVALID_RESPONSE", message: "Malformed server response." });
  }
  if (!envelope.success) {
    throw new ApiError(response.status, envelope.error ?? { code: "REQUEST_FAILED", message: "Request failed." });
  }
  return envelope.data as T;
}

export function buildQuery(params: Record<string, string | number | boolean | null | undefined>): string {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value === null || value === undefined || value === "") return;
    search.set(key, String(value));
  });
  const query = search.toString();
  return query ? `?${query}` : "";
}

export const api = {
  login: (email: string, password: string, workspaceSlug?: string) =>
    apiRequest<import("./types").LoginResponse>("/api/v1/auth/login", {
      method: "POST",
      body: { email, password, workspaceSlug: workspaceSlug || null },
    }),
  verifyMfa: (challengeToken: string, code: string) =>
    apiRequest<import("./types").LoginResponse>("/api/v1/auth/mfa/verify", {
      method: "POST",
      body: { challengeToken, code },
    }),
  startMfaEnrolment: (enrolmentToken: string) =>
    apiRequest<{ provisioningUri: string; secret: string }>("/api/v1/auth/mfa/enrol/start", {
      method: "POST",
      body: { enrolmentToken },
    }),
  confirmMfaEnrolment: (enrolmentToken: string, code: string) =>
    apiRequest<import("./types").LoginResponse>("/api/v1/auth/mfa/enrol/confirm", {
      method: "POST",
      body: { enrolmentToken, code },
    }),
  logout: () => apiRequest<{ status: string }>("/api/v1/auth/logout", { method: "POST" }),
  me: () => apiRequest<import("./types").SessionInfo>("/api/v1/auth/me"),

  dashboard: (from?: string, to?: string) =>
    apiRequest<import("./types").DashboardView>(`/api/v1/dashboard${buildQuery({ from, to })}`),

  activityFeed: (page = 0, size = 15) =>
    apiRequest<PageResponse<import("./types").FeedItem>>(
      `/api/v1/dashboard/activity${buildQuery({ page, size })}`,
    ),

  leads: (params: Record<string, string | number | boolean | null | undefined>) =>
    apiRequest<PageResponse<import("./types").LeadSummary>>(`/api/v1/leads${buildQuery(params)}`),
  lead: (id: string) => apiRequest<import("./types").LeadDetail>(`/api/v1/leads/${id}`),
  createLead: (body: unknown, idempotencyKey: string) =>
    apiRequest<import("./types").LeadDetail>("/api/v1/leads", {
      method: "POST",
      body,
      headers: { "Idempotency-Key": idempotencyKey },
    }),
  updateLead: (id: string, body: unknown) =>
    apiRequest<import("./types").LeadDetail>(`/api/v1/leads/${id}`, { method: "PATCH", body }),
  transitionLead: (id: string, body: unknown) =>
    apiRequest<import("./types").LeadDetail>(`/api/v1/leads/${id}/transition`, { method: "POST", body }),
  assignLead: (id: string, body: unknown) =>
    apiRequest<import("./types").LeadDetail>(`/api/v1/leads/${id}/assign`, { method: "POST", body }),
  mergeLeads: (body: unknown) =>
    apiRequest<import("./types").LeadDetail>("/api/v1/leads/merge", { method: "POST", body }),
  bulkAssignLeads: (body: unknown) =>
    apiRequest<{ assigned: number; failed: { leadId: string; reason: string }[] }>(
      "/api/v1/leads/bulk-assign",
      { method: "POST", body },
    ),
  archiveLead: (id: string, reason: string) =>
    apiRequest<{ status: string }>(`/api/v1/leads/${id}/archive`, { method: "POST", body: { reason } }),
  qualifyLead: (id: string, body: unknown) =>
    apiRequest<import("./types").LeadDetail>(`/api/v1/leads/${id}/qualify`, { method: "POST", body }),

  accounts: (params: Record<string, string | number | boolean | null | undefined>) =>
    apiRequest<PageResponse<import("./types").AccountSummary>>(`/api/v1/accounts${buildQuery(params)}`),
  account: (id: string) => apiRequest<import("./types").AccountDetail>(`/api/v1/accounts/${id}`),
  createAccount: (body: unknown) =>
    apiRequest<import("./types").AccountDetail>("/api/v1/accounts", { method: "POST", body }),

  contact: (id: string) => apiRequest<import("./types").ContactSummary>(`/api/v1/contacts/${id}`),
  contacts: (params: Record<string, string | number | boolean | null | undefined>) =>
    apiRequest<PageResponse<import("./types").ContactSummary>>(`/api/v1/contacts${buildQuery(params)}`),

  pipeline: () => apiRequest<import("./types").PipelineBoard>("/api/v1/opportunities/board"),
  opportunity: (id: string) =>
    apiRequest<import("./types").OpportunityDetail>(`/api/v1/opportunities/${id}`),
  transitionOpportunity: (id: string, body: unknown) =>
    apiRequest<import("./types").OpportunityDetail>(`/api/v1/opportunities/${id}/transition`, {
      method: "POST",
      body,
    }),
  createOpportunity: (body: unknown) =>
    apiRequest<import("./types").OpportunityDetail>("/api/v1/opportunities", { method: "POST", body }),

  tasks: (params: Record<string, string | number | boolean | null | undefined>) =>
    apiRequest<PageResponse<import("./types").TaskView>>(`/api/v1/tasks${buildQuery(params)}`),
  createTask: (body: unknown) => apiRequest<import("./types").TaskView>("/api/v1/tasks", {
    method: "POST", body,
  }),
  completeTask: (id: string, note?: string) =>
    apiRequest<import("./types").TaskView>(`/api/v1/tasks/${id}/complete`, {
      method: "POST",
      body: { completionNote: note ?? null },
    }),

  timeline: (relatedType: string, relatedId: string, page = 0, size = 25) =>
    apiRequest<PageResponse<import("./types").ActivityView>>(
      `/api/v1/records/${relatedType}/${relatedId}/activities${buildQuery({ page, size })}`,
    ),
  recordActivity: (body: unknown) =>
    apiRequest<import("./types").ActivityView>("/api/v1/activities", { method: "POST", body }),

  notifications: (params: Record<string, string | number | boolean | null | undefined>) =>
    apiRequest<PageResponse<import("./types").NotificationView>>(
      `/api/v1/notifications${buildQuery(params)}`,
    ),
  unreadCount: () => apiRequest<{ count: number }>("/api/v1/notifications/unread-count"),
  markNotificationRead: (id: string) =>
    apiRequest<{ status: string }>(`/api/v1/notifications/${id}/read`, { method: "POST" }),

  search: (q: string) => apiRequest<import("./types").SearchHit[]>(`/api/v1/search${buildQuery({ q })}`),

  users: (params: Record<string, string | number | boolean | null | undefined>) =>
    apiRequest<PageResponse<import("./types").UserSummary>>(`/api/v1/users${buildQuery(params)}`),
  roles: () => apiRequest<import("./types").RoleSummary[]>("/api/v1/roles"),
  createUser: (body: unknown) =>
    apiRequest<import("./types").UserSummary>("/api/v1/users", { method: "POST", body }),
  updateUser: (id: string, body: unknown) =>
    apiRequest<import("./types").UserSummary>(`/api/v1/users/${id}`, { method: "PATCH", body }),

  audit: (params: Record<string, string | number | boolean | null | undefined>) =>
    apiRequest<PageResponse<import("./types").AuditView>>(`/api/v1/audit-logs${buildQuery(params)}`),

  services: () => apiRequest<import("./types").ServiceView[]>("/api/v1/catalog/services"),
  stages: () => apiRequest<import("./types").StageView[]>("/api/v1/catalog/stages"),
  sources: () => apiRequest<import("./types").SimpleRef[]>("/api/v1/catalog/sources"),
  industries: () => apiRequest<import("./types").SimpleRef[]>("/api/v1/catalog/industries"),
  lossReasons: () => apiRequest<import("./types").SimpleRef[]>("/api/v1/catalog/loss-reasons"),
  updateService: (id: string, body: unknown) =>
    apiRequest<import("./types").ServiceView>(`/api/v1/catalog/services/${id}`, { method: "PATCH", body }),
  updateStage: (id: string, body: unknown) =>
    apiRequest<import("./types").StageView>(`/api/v1/catalog/stages/${id}`, { method: "PATCH", body }),

  jobs: (params: Record<string, string | number | boolean | null | undefined>) =>
    apiRequest<PageResponse<import("./types").JobView>>(`/api/v1/admin/jobs${buildQuery(params)}`),

  // --- Engagement ---------------------------------------------------------
  emails: (params: Record<string, string | number | boolean | null | undefined>) =>
    apiRequest<PageResponse<import("./types").EmailView>>(`/api/v1/emails${buildQuery(params)}`),
  email: (id: string) => apiRequest<import("./types").EmailDetail>(`/api/v1/emails/${id}`),
  composeEmail: (body: unknown) =>
    apiRequest<import("./types").DraftResult>("/api/v1/emails", { method: "POST", body }),
  approveEmail: (id: string, note?: string) =>
    apiRequest<import("./types").EmailView>(`/api/v1/emails/${id}/approve`, {
      method: "POST",
      body: { note: note ?? null },
    }),
  sendEmail: (id: string) =>
    apiRequest<import("./types").SendResult>(`/api/v1/emails/${id}/send`, { method: "POST" }),
  cancelEmail: (id: string, reason: string) =>
    apiRequest<import("./types").EmailView>(`/api/v1/emails/${id}/cancel`, {
      method: "POST",
      body: { reason },
    }),
  emailEligibility: (email: string) =>
    apiRequest<import("./types").EligibilityView>(`/api/v1/emails/eligibility${buildQuery({ email })}`),

  templates: () => apiRequest<import("./types").TemplateView[]>("/api/v1/email-templates"),
  template: (id: string) => apiRequest<import("./types").TemplateView>(`/api/v1/email-templates/${id}`),
  createTemplate: (body: unknown) =>
    apiRequest<import("./types").TemplateView>("/api/v1/email-templates", { method: "POST", body }),
  updateTemplate: (id: string, body: unknown) =>
    apiRequest<import("./types").TemplateView>(`/api/v1/email-templates/${id}`, { method: "POST", body }),
  submitTemplate: (id: string) =>
    apiRequest<import("./types").TemplateView>(`/api/v1/email-templates/${id}/submit`, { method: "POST" }),
  approveTemplate: (id: string) =>
    apiRequest<import("./types").TemplateView>(`/api/v1/email-templates/${id}/approve`, { method: "POST" }),
  previewTemplate: (subject: string, body: string) =>
    apiRequest<import("./types").TemplatePreview>("/api/v1/email-templates/preview", {
      method: "POST",
      body: { subject, body },
    }),

  smtpProfiles: () => apiRequest<import("./types").SmtpProfileView[]>("/api/v1/smtp-profiles"),
  createSmtpProfile: (body: unknown) =>
    apiRequest<import("./types").SmtpProfileView>("/api/v1/smtp-profiles", { method: "POST", body }),
  updateSmtpProfile: (id: string, body: unknown) =>
    apiRequest<import("./types").SmtpProfileView>(`/api/v1/smtp-profiles/${id}`, { method: "PATCH", body }),
  testSmtpProfile: (id: string, recipient: string, expectedRowVersion?: number) =>
    apiRequest<import("./types").SmtpTestResult>(`/api/v1/smtp-profiles/${id}/test`, {
      method: "POST",
      body: { recipient, expectedRowVersion: expectedRowVersion ?? null },
    }),
  setSmtpStatus: (id: string, status: string) =>
    apiRequest<import("./types").SmtpProfileView>(`/api/v1/smtp-profiles/${id}/status`, {
      method: "POST",
      body: { status },
    }),

  sequences: () => apiRequest<import("./types").SequenceView[]>("/api/v1/sequences"),
  createSequence: (body: unknown) =>
    apiRequest<import("./types").SequenceView>("/api/v1/sequences", { method: "POST", body }),
  activateSequence: (id: string) =>
    apiRequest<import("./types").SequenceView>(`/api/v1/sequences/${id}/activate`, { method: "POST" }),
  pauseSequence: (id: string) =>
    apiRequest<import("./types").SequenceView>(`/api/v1/sequences/${id}/pause`, { method: "POST" }),
  enrollLead: (id: string, leadId: string, smtpProfileId?: string) =>
    apiRequest<import("./types").EnrollmentView>(`/api/v1/sequences/${id}/enroll`, {
      method: "POST",
      body: { leadId, smtpProfileId: smtpProfileId ?? null },
    }),
  enrollments: (params: Record<string, string | number | boolean | null | undefined>) =>
    apiRequest<PageResponse<import("./types").EnrollmentView>>(`/api/v1/sequences/enrollments${buildQuery(params)}`),
  stopEnrollment: (id: string, reason: string) =>
    apiRequest<import("./types").EnrollmentView>(`/api/v1/sequences/enrollments/${id}/stop`, {
      method: "POST",
      body: { reason },
    }),

  replayJob: (id: string) =>
    apiRequest<{ status: string }>(`/api/v1/admin/jobs/${id}/replay`, { method: "POST" }),

  integrationHealth: () =>
    apiRequest<{ integration: string; status: string; release: string; detail: string }[]>(
      "/api/v1/admin/integration-health",
    ),

  // ---------------------------------------------------------------- two-way mailbox (addendum)
  mailboxAccounts: () => apiRequest<import("./types").MailboxAccountView[]>("/api/v1/email/accounts"),
  createMailbox: (body: unknown) =>
    apiRequest<import("./types").MailboxAccountView>("/api/v1/email/accounts", { method: "POST", body }),
  updateMailbox: (id: string, body: unknown) =>
    apiRequest<import("./types").MailboxAccountView>(`/api/v1/email/accounts/${id}`, {
      method: "PATCH",
      body,
    }),
  testMailbox: (id: string) =>
    apiRequest<import("./types").MailboxTestResult>(`/api/v1/email/accounts/${id}/test`, {
      method: "POST",
    }),
  setMailboxStatus: (id: string, enabled: boolean) =>
    apiRequest<import("./types").MailboxAccountView>(`/api/v1/email/accounts/${id}/status`, {
      method: "POST",
      body: { enabled },
    }),
  syncMailbox: (id: string) =>
    apiRequest<{ status: string; accountId: string; jobId: string }>(
      `/api/v1/email/accounts/${id}/sync`,
      { method: "POST" },
    ),
  syncMailboxNow: (id: string) =>
    apiRequest<import("./types").MailboxSyncResult>(`/api/v1/email/accounts/${id}/sync-now`, {
      method: "POST",
    }),
  mailboxSyncState: (id: string) =>
    apiRequest<import("./types").MailboxSyncState>(`/api/v1/email/accounts/${id}/sync-state`),

  inbox: (params: Record<string, string | number | boolean | null | undefined>) =>
    apiRequest<PageResponse<import("./types").InboxConversation>>(`/api/v1/email/inbox${buildQuery(params)}`),
  inboxStats: () => apiRequest<import("./types").InboxStats>("/api/v1/email/inbox/stats"),
  conversation: (id: string) =>
    apiRequest<import("./types").InboxConversationDetail>(`/api/v1/email/conversations/${id}`),
  markConversationRead: (id: string, read: boolean) =>
    apiRequest<import("./types").InboxConversation>(`/api/v1/email/conversations/${id}/read`, {
      method: "POST",
      body: { read },
    }),
  assignConversation: (id: string, body: unknown) =>
    apiRequest<import("./types").InboxConversation>(`/api/v1/email/conversations/${id}/assign`, {
      method: "POST",
      body,
    }),
  noteConversation: (id: string, note: string) =>
    apiRequest<null>(`/api/v1/email/conversations/${id}/note`, { method: "POST", body: { note } }),
  closeConversation: (id: string) =>
    apiRequest<import("./types").InboxConversation>(`/api/v1/email/conversations/${id}/close`, {
      method: "POST",
    }),
  replyConversation: (id: string, body: unknown) =>
    apiRequest<import("./types").InboxMessageView>(`/api/v1/email/conversations/${id}/reply`, {
      method: "POST",
      body,
    }),
  analyzeMessage: (id: string) =>
    apiRequest<import("./types").InboxAnalysis>(`/api/v1/email/messages/${id}/analyze`, { method: "POST" }),

  emailApprovals: (params: Record<string, string | number | boolean | null | undefined>) =>
    apiRequest<PageResponse<import("./types").EmailApprovalView>>(
      `/api/v1/email/approvals${buildQuery(params)}`,
    ),
  approveDraft: (id: string) =>
    apiRequest<import("./types").EmailApprovalView>(`/api/v1/email/approvals/${id}/approve`, {
      method: "POST",
    }),
  rejectDraft: (id: string, reason: string) =>
    apiRequest<import("./types").EmailApprovalView>(`/api/v1/email/approvals/${id}/reject`, {
      method: "POST",
      body: { reason },
    }),

  mailboxRules: () => apiRequest<import("./types").MailboxRuleView[]>("/api/v1/email/rules"),
  createMailboxRule: (body: unknown) =>
    apiRequest<import("./types").MailboxRuleView>("/api/v1/email/rules", { method: "POST", body }),
  setMailboxRuleEnabled: (id: string, enabled: boolean) =>
    apiRequest<import("./types").MailboxRuleView>(`/api/v1/email/rules/${id}/enabled`, {
      method: "POST",
      body: { enabled },
    }),
};
