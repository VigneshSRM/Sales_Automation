"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import { formatDateTime, formatNumber, relativeTime, titleCase } from "@/lib/format";
import {
  Badge, Card, EmptyState, ErrorState, Field, KpiCard, Modal, PageHeader, StatusBadge,
  TableSkeleton, Tabs,
} from "@/components/ui";
import { useToast } from "@/components/toast";
import { useCapability } from "@/lib/hooks";
import type { EmailApprovalView, InboxConversation, InboxMessageView } from "@/lib/types";

/**
 * Unified two-way inbox (addendum 4).
 *
 * Reads: conversations, thread timeline, the latest schema-validated AI analysis and the response
 * policy decision that applies to it.
 * Writes: reply, assign, note, close, re-analyse, and approve/reject drafts. Approving a draft only
 * queues it — the SMTP dispatcher performs the send and re-checks suppression, consent and limits.
 *
 * There is deliberately no "send" button that bypasses the queue: when the caller lacks
 * `email.send`, a reply is stored as PENDING_APPROVAL and a second person decides.
 */

const QUEUES = [
  { key: "OPEN", label: "Open" },
  { key: "CLOSED", label: "Closed" },
  { key: "", label: "All" },
];

export default function UnifiedInboxPage() {
  const [status, setStatus] = useState("OPEN");
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [unassignedOnly, setUnassignedOnly] = useState(false);
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const [replyBody, setReplyBody] = useState("");
  const [sendNow, setSendNow] = useState(false);
  const [noteFor, setNoteFor] = useState(false);
  const [noteBody, setNoteBody] = useState("");
  const [assignFor, setAssignFor] = useState(false);
  const [assignUserId, setAssignUserId] = useState("");
  const [rejectFor, setRejectFor] = useState<EmailApprovalView | null>(null);
  const [rejectReason, setRejectReason] = useState("");

  const queryClient = useQueryClient();
  const toast = useToast();
  const canAssign = useCapability("email.conversations.assign");
  const canSend = useCapability("email.send");
  const canApprove = useCapability("email.drafts.approve");
  const canAnalyze = useCapability("email.analyze");

  const stats = useQuery({ queryKey: ["inbox-stats"], queryFn: api.inboxStats });

  const list = useQuery({
    queryKey: ["inbox", status, unreadOnly, unassignedOnly, search],
    queryFn: () =>
      api.inbox({
        status: status || undefined,
        unreadOnly,
        unassigned: unassignedOnly,
        q: search || undefined,
        page: 0,
        size: 50,
      }),
  });

  const detail = useQuery({
    queryKey: ["inbox-conversation", selected],
    queryFn: () => api.conversation(selected as string),
    enabled: selected !== null,
  });

  const users = useQuery({
    queryKey: ["users-for-assign"],
    queryFn: () => api.users({ page: 0, size: 100 }),
    enabled: assignFor,
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["inbox"] });
    queryClient.invalidateQueries({ queryKey: ["inbox-stats"] });
    queryClient.invalidateQueries({ queryKey: ["inbox-conversation"] });
  };

  const onError = (error: unknown, fallback: string) =>
    toast.push(error instanceof ApiError ? error.message : fallback, "error");

  const reply = useMutation({
    mutationFn: () =>
      api.replyConversation(selected as string, {
        body: replyBody,
        sendImmediately: canSend ? sendNow : false,
      }),
    onSuccess: (message: InboxMessageView) => {
      toast.push(
        message.status === "QUEUED"
          ? "Reply queued for delivery"
          : "Reply saved as a draft and sent to the approval queue",
        "success",
      );
      setReplyBody("");
      invalidate();
    },
    onError: (error) => onError(error, "Reply could not be created"),
  });

  const analyze = useMutation({
    mutationFn: (messageId: string) => api.analyzeMessage(messageId),
    onSuccess: () => {
      toast.push("AI analysis refreshed", "success");
      invalidate();
    },
    onError: (error) => onError(error, "Analysis failed"),
  });

  const approve = useMutation({
    mutationFn: (id: string) => api.approveDraft(id),
    onSuccess: () => {
      toast.push("Draft approved and queued for delivery", "success");
      invalidate();
    },
    onError: (error) => onError(error, "Approval failed"),
  });

  const reject = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => api.rejectDraft(id, reason),
    onSuccess: () => {
      toast.push("Draft rejected", "success");
      setRejectFor(null);
      setRejectReason("");
      invalidate();
    },
    onError: (error) => onError(error, "Rejection failed"),
  });

  const assign = useMutation({
    mutationFn: () =>
      api.assignConversation(selected as string, {
        userId: assignUserId || null,
        queueKey: null,
        note: null,
      }),
    onSuccess: () => {
      toast.push("Conversation reassigned", "success");
      setAssignFor(false);
      invalidate();
    },
    onError: (error) => onError(error, "Assignment failed"),
  });

  const note = useMutation({
    mutationFn: () => api.noteConversation(selected as string, noteBody),
    onSuccess: () => {
      toast.push("Note added to the CRM timeline", "success");
      setNoteFor(false);
      setNoteBody("");
      invalidate();
    },
    onError: (error) => onError(error, "Note could not be added"),
  });

  const close = useMutation({
    mutationFn: () => api.closeConversation(selected as string),
    onSuccess: () => {
      toast.push("Conversation closed", "success");
      invalidate();
    },
    onError: (error) => onError(error, "Conversation could not be closed"),
  });

  const markRead = useMutation({
    mutationFn: ({ id, read }: { id: string; read: boolean }) => api.markConversationRead(id, read),
    onSuccess: () => invalidate(),
    onError: (error) => onError(error, "Could not update the read flag"),
  });

  return (
    <>
      <PageHeader
        title="Unified inbox"
        subtitle="Customer replies arrive from a real mailbox, are deduplicated and threaded, then classified by AI. Nothing is sent without a policy decision."
        actions={
          <button
            type="button"
            className="btn"
            onClick={() => {
              stats.refetch();
              list.refetch();
              if (selected) detail.refetch();
            }}
          >
            Refresh
          </button>
        }
      />

      <div className="grid grid--kpi">
        <KpiCard label="Open conversations" value={formatNumber(stats.data?.totalOpen ?? 0)} unit="threads" definition="Conversations still open in this workspace" />
        <KpiCard label="Unread" value={formatNumber(stats.data?.unread ?? 0)} unit="threads" definition="Threads with at least one unread inbound message" />
        <KpiCard label="Unassigned" value={formatNumber(stats.data?.unassigned ?? 0)} unit="threads" definition="No owner yet — nobody is accountable for the reply" />
        <KpiCard label="Awaiting approval" value={formatNumber(stats.data?.awaitingApproval ?? 0)} unit="drafts" definition="AI or human drafts blocked by the response policy" />
      </div>

      <Tabs
        tabs={QUEUES}
        active={status}
        onChange={(key) => setStatus(key)}
      />

      <div className="row" style={{ gap: 16, marginBottom: 16, flexWrap: "wrap" }}>
        <label className="row small" style={{ gap: 6 }}>
          <input type="checkbox" checked={unreadOnly} onChange={(event) => setUnreadOnly(event.target.checked)} />
          Unread only
        </label>
        <label className="row small" style={{ gap: 6 }}>
          <input
            type="checkbox"
            checked={unassignedOnly}
            onChange={(event) => setUnassignedOnly(event.target.checked)}
          />
          Unassigned only
        </label>
        <input
          type="search"
          aria-label="Search conversations"
          placeholder="Search subject or mailbox"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          style={{ maxWidth: 320 }}
        />
      </div>

      <div className="grid" style={{ gridTemplateColumns: selected ? "minmax(280px, 1fr) 2fr" : "1fr", gap: 16 }}>
        <Card title="Conversations" padded={false}>
          {list.isPending && <TableSkeleton rows={6} />}
          {list.isError && (
            <ErrorState
              message={list.error instanceof ApiError ? list.error.message : "Could not load the inbox."}
              onRetry={() => list.refetch()}
            />
          )}
          {list.isSuccess && list.data.items.length === 0 && (
            <EmptyState
              title="No conversations match these filters"
              description="Replies appear here once an active mailbox syncs an inbound message."
            />
          )}
          {list.isSuccess && list.data.items.length > 0 && (
            <ul className="list">
              {list.data.items.map((conversation: InboxConversation) => (
                <li key={conversation.id}>
                  <button
                    type="button"
                    className={`list__row${conversation.id === selected ? " is-active" : ""}`}
                    onClick={() => {
                      setSelected(conversation.id);
                      if (conversation.unread) markRead.mutate({ id: conversation.id, read: true });
                    }}
                  >
                    <span className="row row--between">
                      <strong className="small">
                        {conversation.unread && <span className="dot dot--accent" aria-label="Unread" />}
                        {conversation.subject || "(no subject)"}
                      </strong>
                      <span className="small muted">{relativeTime(conversation.lastMessageAt)}</span>
                    </span>
                    <span className="small muted">
                      {conversation.contactName ?? conversation.leadName ?? "Unmatched"} ·{" "}
                      {conversation.mailboxAddress}
                    </span>
                    <span className="row" style={{ gap: 6, marginTop: 4 }}>
                      <StatusBadge status={conversation.status} />
                      {conversation.priority === "HIGH" && <Badge tone="danger">High priority</Badge>}
                      {conversation.awaitingApproval && <Badge tone="warning">Approval pending</Badge>}
                      {conversation.matchReason === "UNASSIGNED" && <Badge tone="neutral">No CRM match</Badge>}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Card>

        {selected && (
          <Card
            title={detail.data?.summary.subject ?? "Conversation"}
            actions={
              <div className="row" style={{ gap: 6 }}>
                {canAssign && (
                  <button type="button" className="btn btn--sm" onClick={() => setAssignFor(true)}>
                    Assign
                  </button>
                )}
                <button type="button" className="btn btn--sm" onClick={() => setNoteFor(true)}>
                  Add note
                </button>
                {canAssign && detail.data?.summary.status === "OPEN" && (
                  <button
                    type="button"
                    className="btn btn--sm"
                    disabled={close.isPending}
                    onClick={() => close.mutate()}
                  >
                    Close
                  </button>
                )}
                <button type="button" className="btn btn--sm" onClick={() => setSelected(null)}>
                  Hide
                </button>
              </div>
            }
          >
            {detail.isPending && <TableSkeleton rows={5} />}
            {detail.isError && (
              <ErrorState
                message={
                  detail.error instanceof ApiError ? detail.error.message : "Could not load the conversation."
                }
                onRetry={() => detail.refetch()}
              />
            )}
            {detail.isSuccess && (
              <div className="stack">
                {detail.data.summary.assignedUserName ? (
                  <p className="small muted">
                    Owner: {detail.data.summary.assignedUserName}
                    {detail.data.summary.queueKey ? ` · queue ${detail.data.summary.queueKey}` : ""}
                  </p>
                ) : (
                  <p className="small" style={{ color: "var(--warning)" }}>
                    Unassigned — assign an owner so this reply has accountability.
                  </p>
                )}

                <ol className="timeline">
                  {detail.data.messages.map((message) => (
                    <li key={message.id} className="timeline__item">
                      <span className="timeline__rail" aria-hidden="true">
                        <span className="timeline__dot" />
                        <span className="timeline__line" />
                      </span>
                      <span className="stack" style={{ gap: 4 }}>
                        <span className="row row--between">
                          <strong className="small">
                            {message.direction === "INBOUND" ? "Received" : "Sent"} ·{" "}
                            {message.fromEmail ?? "—"}
                          </strong>
                          <span className="small muted">
                            {formatDateTime(message.receivedAt ?? message.sentAt)}
                          </span>
                        </span>
                        <span className="row" style={{ gap: 6 }}>
                          <StatusBadge status={message.status} />
                          {message.direction === "OUTBOUND" && (
                            <Badge tone={message.status === "SENT" ? "success" : "neutral"}>
                              {message.status === "PENDING_APPROVAL" ? "Draft · awaiting approval" : titleCase(message.status)}
                            </Badge>
                          )}
                          {message.aiStatus && <Badge tone="neutral">AI {titleCase(message.aiStatus)}</Badge>}
                        </span>
                        <span className="small" style={{ whiteSpace: "pre-wrap" }}>
                          {message.bodyText}
                        </span>
                        {message.attachments.length > 0 && (
                          <span className="small muted">
                            {message.attachments.length} attachment(s):{" "}
                            {message.attachments.map((file) => file.filename).join(", ")}
                          </span>
                        )}
                      </span>
                    </li>
                  ))}
                </ol>

                <Card title="AI analysis and response policy">
                  {!detail.data.analysis ? (
                    <div className="stack">
                      <p className="small muted">
                        This message has not been analysed yet. Analysis is queued automatically after each
                        sync; providers must be configured for it to run.
                      </p>
                      {canAnalyze && (
                        <button
                          type="button"
                          className="btn btn--sm"
                          disabled={analyze.isPending}
                          onClick={() => {
                            const inbound = [...detail.data.messages]
                              .reverse()
                              .find((message) => message.direction === "INBOUND");
                            if (inbound) analyze.mutate(inbound.id);
                          }}
                        >
                          Run AI analysis
                        </button>
                      )}
                    </div>
                  ) : (
                    <div className="stack" style={{ gap: 8 }}>
                      <div className="row" style={{ gap: 8, flexWrap: "wrap" }}>
                        <Badge tone="accent">{titleCase(detail.data.analysis.intent)}</Badge>
                        <Badge tone="neutral">
                          Confidence {Math.round(detail.data.analysis.confidence * 100)}%
                        </Badge>
                        {detail.data.analysis.urgency && (
                          <Badge tone={detail.data.analysis.urgency === "CRITICAL" ? "danger" : "neutral"}>
                            Urgency {titleCase(detail.data.analysis.urgency)}
                          </Badge>
                        )}
                        {detail.data.policy && (
                          <Badge
                            tone={
                              detail.data.policy.mode === "AUTOMATIC"
                                ? "success"
                                : detail.data.policy.mode === "APPROVAL_REQUIRED"
                                  ? "warning"
                                  : "neutral"
                            }
                          >
                            Policy {titleCase(detail.data.policy.mode)} · {detail.data.policy.reasonCode}
                          </Badge>
                        )}
                      </div>
                      {detail.data.analysis.recommendedAction && (
                        <p className="small">{detail.data.analysis.recommendedAction}</p>
                      )}
                      {detail.data.analysis.questions.length > 0 && (
                        <div className="small">
                          <strong>Customer asks</strong>
                          <ul>
                            {detail.data.analysis.questions.map((question) => (
                              <li key={question}>{question}</li>
                            ))}
                          </ul>
                        </div>
                      )}
                      {detail.data.analysis.risks.length > 0 && (
                        <div className="small" style={{ color: "var(--danger)" }}>
                          <strong>Risks</strong>
                          <ul>
                            {detail.data.analysis.risks.map((risk) => (
                              <li key={risk}>{risk}</li>
                            ))}
                          </ul>
                        </div>
                      )}
                      <p className="small muted">
                        {detail.data.analysis.provider} · {detail.data.analysis.model} ·{" "}
                        {detail.data.analysis.promptVersion} ·{" "}
                        {detail.data.analysis.validated ? "schema validated" : "not validated"}
                      </p>
                    </div>
                  )}
                </Card>

                {detail.data.approval && (
                  <Card title="Draft awaiting a decision">
                    <div className="stack" style={{ gap: 8 }}>
                      <p className="small muted">
                        Requested by {detail.data.approval.requestedByName}
                        {detail.data.approval.expiresAt
                          ? ` · expires ${formatDateTime(detail.data.approval.expiresAt)}`
                          : ""}
                        {detail.data.approval.reasonCode
                          ? ` · reason ${detail.data.approval.reasonCode}`
                          : ""}
                      </p>
                      {detail.data.aiDraft && (
                        <div className="template-preview" style={{ whiteSpace: "pre-wrap" }}>
                          {detail.data.aiDraft}
                        </div>
                      )}
                      <div className="row" style={{ gap: 8 }}>
                        {canApprove && (
                          <>
                            <button
                              type="button"
                              className="btn btn--sm btn--primary"
                              disabled={approve.isPending}
                              onClick={() => approve.mutate(detail.data.approval!.id)}
                            >
                              Approve and queue
                            </button>
                            <button
                              type="button"
                              className="btn btn--sm btn--danger"
                              onClick={() => setRejectFor(detail.data.approval)}
                            >
                              Reject
                            </button>
                          </>
                        )}
                        {!canApprove && (
                          <p className="small muted">
                            You do not hold <code>email.drafts.approve</code>; another user must decide.
                          </p>
                        )}
                      </div>
                    </div>
                  </Card>
                )}

                <Card title="Reply">
                  <div className="stack">
                    <Field
                      label="Message"
                      hint="HTML is generated from this plain text and escaped — no raw markup is accepted."
                      htmlFor="reply-body"
                    >
                      <textarea
                        id="reply-body"
                        rows={6}
                        value={replyBody}
                        onChange={(event) => setReplyBody(event.target.value)}
                        placeholder={
                          detail.data.aiDraft ? "Start from the AI draft above, or write your own reply" : "Write a reply"
                        }
                      />
                    </Field>
                    {detail.data.aiDraft && (
                      <button
                        type="button"
                        className="btn btn--sm"
                        onClick={() => setReplyBody(detail.data.aiDraft ?? "")}
                      >
                        Use AI draft as a starting point
                      </button>
                    )}
                    <label className="row small" style={{ gap: 6 }}>
                      <input
                        type="checkbox"
                        checked={canSend && sendNow}
                        disabled={!canSend}
                        onChange={(event) => setSendNow(event.target.checked)}
                      />
                      Send immediately after approval-free checks{" "}
                      {!canSend && "(needs email.send — your reply will wait for approval)"}
                    </label>
                    <button
                      type="button"
                      className="btn btn--primary"
                      disabled={replyBody.trim().length < 3 || reply.isPending}
                      onClick={() => reply.mutate()}
                    >
                      {canSend && sendNow ? "Queue reply" : "Save draft for approval"}
                    </button>
                  </div>
                </Card>
              </div>
            )}
          </Card>
        )}
      </div>

      {assignFor && (
        <Modal
          title="Assign conversation"
          onClose={() => setAssignFor(false)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setAssignFor(false)}>
                Cancel
              </button>
              <button
                type="button"
                className="btn btn--primary"
                disabled={assign.isPending || assignUserId === ""}
                onClick={() => assign.mutate()}
              >
                Assign
              </button>
            </>
          }
        >
          <Field label="Owner" htmlFor="assign-user">
            <select
              id="assign-user"
              value={assignUserId}
              onChange={(event) => setAssignUserId(event.target.value)}
            >
              <option value="">Choose a user…</option>
              {(users.data?.items ?? []).map((user) => (
                <option key={user.id} value={user.id}>
                  {user.displayName}
                </option>
              ))}
            </select>
          </Field>
        </Modal>
      )}

      {noteFor && (
        <Modal
          title="Add internal note"
          onClose={() => setNoteFor(false)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setNoteFor(false)}>
                Cancel
              </button>
              <button
                type="button"
                className="btn btn--primary"
                disabled={noteBody.trim().length < 3 || note.isPending}
                onClick={() => note.mutate()}
              >
                Add note
              </button>
            </>
          }
        >
          <Field
            label="Note"
            hint="Written to the CRM timeline of the linked lead or contact; never emailed to the customer."
            htmlFor="note-body"
          >
            <textarea
              id="note-body"
              rows={4}
              value={noteBody}
              onChange={(event) => setNoteBody(event.target.value)}
            />
          </Field>
        </Modal>
      )}

      {rejectFor && (
        <Modal
          title="Reject draft"
          onClose={() => setRejectFor(null)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setRejectFor(null)}>
                Keep draft
              </button>
              <button
                type="button"
                className="btn btn--danger"
                disabled={rejectReason.trim().length < 3 || reject.isPending}
                onClick={() => reject.mutate({ id: rejectFor.id, reason: rejectReason })}
              >
                Reject draft
              </button>
            </>
          }
        >
          <Field label="Reason (recorded in the audit log)" htmlFor="reject-reason">
            <textarea
              id="reject-reason"
              rows={3}
              value={rejectReason}
              onChange={(event) => setRejectReason(event.target.value)}
            />
          </Field>
        </Modal>
      )}
    </>
  );
}
