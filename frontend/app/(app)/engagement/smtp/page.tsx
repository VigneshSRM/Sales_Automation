"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import { formatDateTime, titleCase } from "@/lib/format";
import {
  Card, DataTable, EmptyState, ErrorState, Field, Modal, PageHeader, StatusBadge,
  TableSkeleton, type Column,
} from "@/components/ui";
import { useToast } from "@/components/toast";
import { useCapability } from "@/lib/hooks";
import type { SmtpProfileView } from "@/lib/types";

/**
 * Outbound SMTP accounts.
 *
 * Secrets are write-only: the form never receives a stored password, and the API never
 * returns one. A profile can only be activated after a live test has succeeded — that rule
 * is enforced server-side; the UI merely reflects it.
 */

type NewProfile = {
  name: string;
  fromName: string;
  fromEmail: string;
  replyToEmail: string;
  host: string;
  port: number;
  tlsMode: string;
  username: string;
  secret: string;
  dailyLimit: number;
  hourlyLimit: number;
};

const EMPTY: NewProfile = {
  name: "",
  fromName: "",
  fromEmail: "",
  replyToEmail: "",
  host: "",
  port: 587,
  tlsMode: "STARTTLS",
  username: "",
  secret: "",
  dailyLimit: 200,
  hourlyLimit: 50,
};

export default function SmtpProfilesPage() {
  const queryClient = useQueryClient();
  const toast = useToast();
  const canManage = useCapability("smtp.manage");
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<NewProfile>({ ...EMPTY });
  const [testFor, setTestFor] = useState<SmtpProfileView | null>(null);
  const [testRecipient, setTestRecipient] = useState("");

  const profiles = useQuery({ queryKey: ["smtp-profiles"], queryFn: api.smtpProfiles });
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["smtp-profiles"] });

  const create = useMutation({
    mutationFn: () =>
      api.createSmtpProfile({
        ...draft,
        replyToEmail: draft.replyToEmail || null,
      }),
    onSuccess: () => {
      toast.push("Profile created in PENDING_TEST — run a test to activate it", "success");
      setCreating(false);
      setDraft({ ...EMPTY });
      invalidate();
    },
    onError: (error) =>
      toast.push(error instanceof ApiError ? error.message : "Could not create the profile", "error"),
  });

  const test = useMutation({
    mutationFn: (profile: SmtpProfileView) =>
      api.testSmtpProfile(profile.id, testRecipient || profile.fromEmail, profile.rowVersion),
    onSuccess: (result) => {
      toast.push(
        result.delivered
          ? `Test delivered via ${result.host}:${result.port} (${result.tlsMode})`
          : `Test failed: ${result.detail}`,
        result.delivered ? "success" : "error",
      );
      setTestFor(null);
      setTestRecipient("");
      invalidate();
    },
    onError: (error) =>
      toast.push(error instanceof ApiError ? error.message : "Test could not be started", "error"),
  });

  const setStatus = useMutation({
    mutationFn: ({ id, status }: { id: string; status: string }) => api.setSmtpStatus(id, status),
    onSuccess: () => {
      toast.push("Profile status updated", "success");
      invalidate();
    },
    onError: (error) =>
      toast.push(error instanceof ApiError ? error.message : "Status change rejected", "error"),
  });

  const columns: Column<SmtpProfileView>[] = [
    {
      key: "name",
      header: "Account",
      render: (row) => (
        <span>
          <strong>{row.name}</strong>
          <div className="small muted">
            {row.fromName} &lt;{row.fromEmail}&gt;
          </div>
        </span>
      ),
    },
    {
      key: "server",
      header: "Server",
      render: (row) => (
        <span className="small mono">
          {row.host}:{row.port} · {row.tlsMode}
        </span>
      ),
    },
    { key: "status", header: "Status", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "limit",
      header: "Today / limit",
      align: "right",
      render: (row) => (
        <span>
          {row.sentToday}
          <span className="muted"> / {row.dailyLimit}</span>
        </span>
      ),
    },
    {
      key: "lastTestedAt",
      header: "Last test",
      render: (row) =>
        row.lastTestedAt ? (
          <span className="small" title={row.lastTestDetail ?? undefined}>
            {formatDateTime(row.lastTestedAt)}
          </span>
        ) : (
          <span className="small muted">never</span>
        ),
    },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) =>
        canManage ? (
          <div className="row" style={{ justifyContent: "flex-end", gap: 6 }}>
            <button type="button" className="btn btn--sm" onClick={() => setTestFor(row)}>
              Test
            </button>
            {row.status === "ACTIVE" ? (
              <button
                type="button"
                className="btn btn--sm btn--danger"
                disabled={setStatus.isPending}
                onClick={() => setStatus.mutate({ id: row.id, status: "DISABLED" })}
              >
                Disable
              </button>
            ) : (
              <button
                type="button"
                className="btn btn--sm btn--primary"
                disabled={setStatus.isPending || !row.lastTestedAt}
                title={!row.lastTestedAt ? "Run a successful test before activating" : undefined}
                onClick={() => setStatus.mutate({ id: row.id, status: "ACTIVE" })}
              >
                Activate
              </button>
            )}
          </div>
        ) : null,
    },
  ];

  return (
    <>
      <PageHeader
        title="SMTP accounts"
        subtitle="Multiple independently configured sending accounts. Credentials are encrypted (AES-256-GCM) and never returned by the API."
        actions={
          canManage ? (
            <button type="button" className="btn btn--primary btn--sm" onClick={() => setCreating(true)}>
              Add account
            </button>
          ) : null
        }
      />

      <Card padded={false}>
        {profiles.isPending && <TableSkeleton rows={4} />}
        {profiles.isError && (
          <ErrorState
            message={profiles.error instanceof ApiError ? profiles.error.message : "Could not load SMTP accounts."}
            onRetry={() => profiles.refetch()}
          />
        )}
        {profiles.isSuccess && profiles.data.length === 0 && (
          <EmptyState
            title="No sending account configured"
            description="Add an SMTP account, run the live test, then activate it. Email sending stays blocked until then."
          />
        )}
        {profiles.isSuccess && profiles.data.length > 0 && (
          <DataTable columns={columns} rows={profiles.data} caption="SMTP accounts" />
        )}
      </Card>

      {creating && (
        <Modal
          title="Add SMTP account"
          onClose={() => setCreating(false)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setCreating(false)}>
                Discard
              </button>
              <button
                type="button"
                className="btn btn--primary"
                disabled={
                  create.isPending ||
                  !draft.name ||
                  !draft.host ||
                  !draft.username ||
                  !draft.secret ||
                  !draft.fromEmail
                }
                onClick={() => create.mutate()}
              >
                Create profile
              </button>
            </>
          }
        >
          <div className="stack">
            <div className="form-grid">
              <Field label="Profile name" htmlFor="smtp-name" hint="Unique within the workspace, 2–80 characters.">
                <input
                  id="smtp-name"
                  value={draft.name}
                  onChange={(event) => setDraft({ ...draft, name: event.target.value })}
                />
              </Field>
              <Field label="From name" htmlFor="smtp-from-name">
                <input
                  id="smtp-from-name"
                  value={draft.fromName}
                  onChange={(event) => setDraft({ ...draft, fromName: event.target.value })}
                />
              </Field>
              <Field label="From address" htmlFor="smtp-from-email">
                <input
                  id="smtp-from-email"
                  type="email"
                  value={draft.fromEmail}
                  onChange={(event) => setDraft({ ...draft, fromEmail: event.target.value })}
                />
              </Field>
              <Field label="Reply-to (optional)" htmlFor="smtp-reply">
                <input
                  id="smtp-reply"
                  type="email"
                  value={draft.replyToEmail}
                  onChange={(event) => setDraft({ ...draft, replyToEmail: event.target.value })}
                />
              </Field>
              <Field label="Hostname" htmlFor="smtp-host" hint="Hostname only — URLs are rejected.">
                <input
                  id="smtp-host"
                  value={draft.host}
                  onChange={(event) => setDraft({ ...draft, host: event.target.value })}
                />
              </Field>
              <Field label="Port" htmlFor="smtp-port">
                <input
                  id="smtp-port"
                  type="number"
                  min={1}
                  max={65535}
                  value={draft.port}
                  onChange={(event) => setDraft({ ...draft, port: Number(event.target.value) })}
                />
              </Field>
              <Field label="TLS mode" htmlFor="smtp-tls" hint="NONE is rejected in production.">
                <select
                  id="smtp-tls"
                  value={draft.tlsMode}
                  onChange={(event) => setDraft({ ...draft, tlsMode: event.target.value })}
                >
                  <option value="STARTTLS">STARTTLS</option>
                  <option value="IMPLICIT_TLS">Implicit TLS (465)</option>
                  <option value="NONE">None (development only)</option>
                </select>
              </Field>
              <Field label="Username" htmlFor="smtp-user">
                <input
                  id="smtp-user"
                  autoComplete="off"
                  value={draft.username}
                  onChange={(event) => setDraft({ ...draft, username: event.target.value })}
                />
              </Field>
              <Field label="Password / app password" htmlFor="smtp-secret" hint="Encrypted at rest; never shown again.">
                <input
                  id="smtp-secret"
                  type="password"
                  autoComplete="new-password"
                  value={draft.secret}
                  onChange={(event) => setDraft({ ...draft, secret: event.target.value })}
                />
              </Field>
              <Field label="Daily limit" htmlFor="smtp-daily">
                <input
                  id="smtp-daily"
                  type="number"
                  min={1}
                  max={5000}
                  value={draft.dailyLimit}
                  onChange={(event) => setDraft({ ...draft, dailyLimit: Number(event.target.value) })}
                />
              </Field>
              <Field label="Hourly limit" htmlFor="smtp-hourly">
                <input
                  id="smtp-hourly"
                  type="number"
                  min={1}
                  max={1000}
                  value={draft.hourlyLimit}
                  onChange={(event) => setDraft({ ...draft, hourlyLimit: Number(event.target.value) })}
                />
              </Field>
            </div>
          </div>
        </Modal>
      )}

      {testFor && (
        <Modal
          title={`Send a test through ${testFor.name}`}
          onClose={() => setTestFor(null)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setTestFor(null)}>
                Cancel
              </button>
              <button
                type="button"
                className="btn btn--primary"
                disabled={test.isPending || !testRecipient}
                onClick={() => test.mutate(testFor)}
              >
                Send test
              </button>
            </>
          }
        >
          <div className="stack">
            <p className="small muted">
              The test opens a real SMTP connection with the stored credentials and sends a single
              message. A successful test is required before the profile can be activated.
            </p>
            <Field label="Recipient" htmlFor="smtp-test-recipient" error={undefined}>
              <input
                id="smtp-test-recipient"
                type="email"
                value={testRecipient}
                placeholder={testFor.fromEmail}
                onChange={(event) => setTestRecipient(event.target.value)}
              />
            </Field>
            {testFor.lastTestDetail && (
              <p className="small muted">Last result: {titleCase(testFor.lastTestDetail)}</p>
            )}
          </div>
        </Modal>
      )}
    </>
  );
}
