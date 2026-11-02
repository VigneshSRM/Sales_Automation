"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import { formatDateTime, titleCase } from "@/lib/format";
import {
  Card, DataTable, EmptyState, ErrorState, Modal, PageHeader, Pagination, StatusBadge,
  TableSkeleton, Tabs, type Column,
} from "@/components/ui";
import { useToast } from "@/components/toast";
import { useCapability } from "@/lib/hooks";
import type { EmailView } from "@/lib/types";

/**
 * Email engagement inbox.
 *
 * Every action maps to a backend command: approve (four-eyes enforced server side),
 * send now (eligibility + daily limit re-checked in the send transaction) and cancel.
 * Nothing is optimistic — the row is replaced with the server's response.
 */

const TABS = [
  { key: "PENDING_APPROVAL", label: "Awaiting approval" },
  { key: "QUEUED", label: "Queued" },
  { key: "SENT", label: "Sent" },
  { key: "REPLIED", label: "Replied" },
  { key: "BOUNCED", label: "Bounced" },
  { key: "CANCELLED", label: "Cancelled" },
];

export default function EmailInboxPage() {
  const [status, setStatus] = useState("PENDING_APPROVAL");
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const [cancelFor, setCancelFor] = useState<EmailView | null>(null);
  const [cancelReason, setCancelReason] = useState("");

  const queryClient = useQueryClient();
  const toast = useToast();
  const canApprove = useCapability("email.approve");
  const canSend = useCapability("email.send");

  const list = useQuery({
    queryKey: ["emails", status, page],
    queryFn: () => api.emails({ status, page, size: 25 }),
  });

  const detail = useQuery({
    queryKey: ["email", selected],
    queryFn: () => api.email(selected as string),
    enabled: selected !== null,
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["emails"] });
    queryClient.invalidateQueries({ queryKey: ["email"] });
    queryClient.invalidateQueries({ queryKey: ["dashboard"] });
  };

  const approve = useMutation({
    mutationFn: (id: string) => api.approveEmail(id),
    onSuccess: () => {
      toast.push("Message approved and queued for delivery", "success");
      invalidate();
    },
    onError: (error) =>
      toast.push(error instanceof ApiError ? error.message : "Approval failed", "error"),
  });

  const send = useMutation({
    mutationFn: (id: string) => api.sendEmail(id),
    onSuccess: (result) => {
      toast.push(
        result.sentAt
          ? `Delivered at ${formatDateTime(result.sentAt)}`
          : `Handed to transport (${titleCase(result.status)})`,
        "success",
      );
      invalidate();
    },
    onError: (error) =>
      toast.push(error instanceof ApiError ? error.message : "Send failed", "error"),
  });

  const cancel = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => api.cancelEmail(id, reason),
    onSuccess: () => {
      toast.push("Message cancelled", "success");
      setCancelFor(null);
      setCancelReason("");
      invalidate();
    },
    onError: (error) =>
      toast.push(error instanceof ApiError ? error.message : "Cancel failed", "error"),
  });

  const columns: Column<EmailView>[] = [
    {
      key: "subject",
      header: "Message",
      render: (row) => (
        <span>
          <button type="button" className="link-strong" onClick={() => setSelected(row.id)}>
            {row.subject || "(no subject)"}
          </button>
          <div className="small muted">
            {row.toName ? `${row.toName} · ` : ""}
            {row.toEmail}
          </div>
        </span>
      ),
    },
    { key: "status", header: "Status", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "smtp",
      header: "Account",
      render: (row) => row.smtpProfileName ?? <span className="muted">—</span>,
    },
    {
      key: "scheduledAt",
      header: "Scheduled",
      render: (row) =>
        row.scheduledAt ? (
          formatDateTime(row.scheduledAt)
        ) : (
          <span className="muted">immediate</span>
        ),
    },
    {
      key: "sentAt",
      header: "Sent",
      render: (row) =>
        row.sentAt ? formatDateTime(row.sentAt) : <span className="muted">—</span>,
    },
    {
      key: "attempts",
      header: "Attempts",
      align: "right",
      render: (row) => String(row.attemptCount),
    },
    {
      key: "actions",
      header: "Actions",
      align: "right",
      render: (row) => (
        <div className="row" style={{ justifyContent: "flex-end", gap: 6 }}>
          {row.status === "PENDING_APPROVAL" && canApprove && (
            <button
              type="button"
              className="btn btn--sm btn--primary"
              disabled={approve.isPending}
              onClick={() => approve.mutate(row.id)}
            >
              Approve
            </button>
          )}
          {(row.status === "QUEUED" || row.status === "SCHEDULED") && canSend && (
            <button
              type="button"
              className="btn btn--sm"
              disabled={send.isPending}
              onClick={() => send.mutate(row.id)}
            >
              Send now
            </button>
          )}
          {["DRAFT", "PENDING_APPROVAL", "SCHEDULED", "QUEUED"].includes(row.status) && (
            <button
              type="button"
              className="btn btn--sm btn--danger"
              onClick={() => setCancelFor(row)}
            >
              Cancel
            </button>
          )}
        </div>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Email engagement"
        subtitle="Outbound messages, approvals and delivery evidence. Eligibility is re-checked at send time."
      />

      <Tabs
        tabs={TABS}
        active={status}
        onChange={(key) => {
          setStatus(key);
          setPage(0);
        }}
      />

      <Card padded={false}>
        {list.isPending && <TableSkeleton rows={6} />}
        {list.isError && (
          <ErrorState
            message={
              list.error instanceof ApiError ? list.error.message : "Could not load messages."
            }
            onRetry={() => list.refetch()}
          />
        )}
        {list.isSuccess && list.data.items.length === 0 && (
          <EmptyState
            title={`No messages in ${titleCase(status)}`}
            description="Compose from a lead detail page or an approved template to create one."
          />
        )}
        {list.isSuccess && list.data.items.length > 0 && (
          <>
            <DataTable columns={columns} rows={list.data.items} caption="Outbound messages" />
            <Pagination
              page={page}
              size={list.data.size}
              totalElements={list.data.totalElements}
              onPage={setPage}
            />
          </>
        )}
      </Card>

      {selected && (
        <Modal
          title={detail.data?.message.subject || "Message"}
          onClose={() => setSelected(null)}
          footer={
            <button type="button" className="btn" onClick={() => setSelected(null)}>
              Close
            </button>
          }
        >
          {detail.isPending && <TableSkeleton rows={4} />}
          {detail.isError && (
            <ErrorState
              message={
                detail.error instanceof ApiError ? detail.error.message : "Could not load the message."
              }
              onRetry={() => detail.refetch()}
            />
          )}
          {detail.isSuccess && (
            <div className="stack">
              <div className="row row--between">
                <span className="small muted">
                  To {detail.data.message.toEmail} · {detail.data.message.attemptCount} attempt(s)
                </span>
                <StatusBadge status={detail.data.message.status} />
              </div>

              {detail.data.message.failureCode && (
                <p className="small" role="alert" style={{ color: "var(--danger)" }}>
                  {detail.data.message.failureCode}: {detail.data.message.failureDetail}
                </p>
              )}

              <div
                className="template-preview"
                // Content is authored by our own composer and rendered from stored HTML.
                // The API returns text-only for untrusted inbound bodies.
                dangerouslySetInnerHTML={{ __html: detail.data.bodyHtml ?? "" }}
              />

              <Card title="Eligibility at this moment" padded={false}>
                <div className="card__body stack" style={{ gap: 8 }}>
                  <p className="small">
                    {detail.data.eligibility.allowed ? (
                      <span className="badge badge--success">Allowed</span>
                    ) : (
                      <span className="badge badge--danger">Blocked</span>
                    )}{" "}
                    Consent: {detail.data.eligibility.consentStatus ?? "unknown"}
                  </p>
                  {detail.data.eligibility.blockers.map((blocker) => (
                    <p key={blocker} className="small" style={{ color: "var(--danger)" }}>
                      {blocker}
                    </p>
                  ))}
                  {detail.data.eligibility.warnings.map((warning) => (
                    <p key={warning} className="small muted">
                      {warning}
                    </p>
                  ))}
                </div>
              </Card>

              <Card title="Delivery events" padded={false}>
                {detail.data.events.length === 0 ? (
                  <div className="card__body small muted">No transport events yet.</div>
                ) : (
                  <ul className="timeline">
                    {detail.data.events.map((event, index) => (
                      <li key={`${event.eventType}-${index}`} className="timeline__item">
                        <span className="timeline__rail" aria-hidden="true">
                          <span className="timeline__dot" />
                          <span className="timeline__line" />
                        </span>
                        <span>
                          <strong className="small">{titleCase(event.eventType)}</strong>
                          <div className="small muted">
                            {formatDateTime(event.occurredAt)}
                            {event.detail ? ` · ${event.detail}` : ""}
                          </div>
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </Card>
            </div>
          )}
        </Modal>
      )}

      {cancelFor && (
        <Modal
          title="Cancel message"
          onClose={() => setCancelFor(null)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setCancelFor(null)}>
                Keep message
              </button>
              <button
                type="button"
                className="btn btn--danger"
                disabled={cancelReason.trim().length < 3 || cancel.isPending}
                onClick={() => cancel.mutate({ id: cancelFor.id, reason: cancelReason })}
              >
                Cancel message
              </button>
            </>
          }
        >
          <div className="field">
            <label className="field__label" htmlFor="cancel-reason">
              Reason (recorded in the audit log)
            </label>
            <textarea
              id="cancel-reason"
              rows={3}
              value={cancelReason}
              onChange={(event) => setCancelReason(event.target.value)}
            />
            <span className="field__hint">
              A message can only be cancelled before it is handed to the SMTP transport.
            </span>
          </div>
        </Modal>
      )}
    </>
  );
}
