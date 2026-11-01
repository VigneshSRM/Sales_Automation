"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import { formatDateTime, titleCase } from "@/lib/format";
import {
  Badge, Card, DataTable, EmptyState, ErrorState, Field, Modal, PageHeader, StatusBadge,
  TableSkeleton, type Column,
} from "@/components/ui";
import { useToast } from "@/components/toast";
import { useCapability } from "@/lib/hooks";
import type { MailboxAccountView } from "@/lib/types";

/**
 * Mailbox connection settings (addendum 3.1 / 8).
 *
 * A mailbox is created in PENDING_TEST and cannot be enabled until a real IMAP session succeeds, so
 * a typo in the host or password can never quietly enter the inbound pipeline. Secrets are write-only:
 * the form never receives a stored credential back from the API.
 */

const PROVIDERS = ["IMAP", "GMAIL_API", "MICROSOFT_GRAPH"] as const;

type FormState = {
  name: string;
  provider: string;
  mailboxAddress: string;
  fromName: string;
  host: string;
  port: string;
  tlsMode: string;
  authMethod: string;
  folder: string;
  username: string;
  secret: string;
  pollIntervalSeconds: string;
  smtpProfileId: string;
};

const EMPTY: FormState = {
  name: "",
  provider: "IMAP",
  mailboxAddress: "",
  fromName: "",
  host: "",
  port: "993",
  tlsMode: "IMPLICIT_TLS",
  authMethod: "PASSWORD",
  folder: "INBOX",
  username: "",
  secret: "",
  pollIntervalSeconds: "300",
  smtpProfileId: "",
};

export default function MailboxSettingsPage() {
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY);
  const queryClient = useQueryClient();
  const toast = useToast();
  const canManage = useCapability("email.accounts.manage");

  const accounts = useQuery({ queryKey: ["mailbox-accounts"], queryFn: api.mailboxAccounts });
  const smtpProfiles = useQuery({ queryKey: ["smtp-profiles"], queryFn: api.smtpProfiles });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["mailbox-accounts"] });
  const onError = (error: unknown, fallback: string) =>
    toast.push(error instanceof ApiError ? error.message : fallback, "error");

  const create = useMutation({
    mutationFn: () =>
      api.createMailbox({
        provider: form.provider,
        name: form.name,
        mailboxAddress: form.mailboxAddress,
        fromName: form.fromName || null,
        inboundEnabled: true,
        outboundEnabled: true,
        smtpProfileId: form.smtpProfileId || null,
        host: form.host || null,
        port: form.port ? Number(form.port) : null,
        tlsMode: form.tlsMode,
        authMethod: form.authMethod,
        folder: form.folder || "INBOX",
        username: form.username || null,
        secret: form.secret,
        oauthRefreshToken: null,
        ownerUserId: null,
        queueKey: null,
        pollIntervalSeconds: form.pollIntervalSeconds ? Number(form.pollIntervalSeconds) : null,
      }),
    onSuccess: () => {
      toast.push("Mailbox created — run a connection test before enabling it", "success");
      setCreating(false);
      setForm(EMPTY);
      invalidate();
    },
    onError: (error) => onError(error, "Mailbox could not be created"),
  });

  const test = useMutation({
    mutationFn: (id: string) => api.testMailbox(id),
    onSuccess: (result) => {
      toast.push(
        result.ok
          ? `Connected: ${result.detail}`
          : `Connection failed: ${result.detail}`,
        result.ok ? "success" : "error",
      );
      invalidate();
    },
    onError: (error) => onError(error, "Connection test failed"),
  });

  const setStatus = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) => api.setMailboxStatus(id, enabled),
    onSuccess: (account) => {
      toast.push(`Mailbox is now ${titleCase(account.status)}`, "success");
      invalidate();
    },
    onError: (error) => onError(error, "Status change failed"),
  });

  const sync = useMutation({
    mutationFn: (id: string) => api.syncMailbox(id),
    onSuccess: () => {
      toast.push("Synchronisation queued — progress is visible in the sync column", "success");
      invalidate();
    },
    onError: (error) => onError(error, "Sync could not be queued"),
  });

  const columns: Column<MailboxAccountView>[] = [
    {
      key: "name",
      header: "Mailbox",
      render: (row) => (
        <span>
          <strong>{row.name}</strong>
          <div className="small muted">
            {row.mailboxAddress} · {row.provider}
          </div>
        </span>
      ),
    },
    { key: "status", header: "Status", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "transport",
      header: "Inbound transport",
      render: (row) =>
        row.provider === "IMAP" ? (
          <span className="small">
            {row.host ?? "—"}:{row.port ?? "—"} · {row.tlsMode ?? "—"} · {row.folder ?? "—"}
          </span>
        ) : (
          <Badge tone="warning">Adapter not available yet</Badge>
        ),
    },
    {
      key: "sync",
      header: "Sync",
      render: (row) =>
        row.sync ? (
          <span className="small">
            UID {row.sync.lastUid} · {titleCase(row.sync.reconciliationStatus)}
            <div className="small muted">
              {row.sync.lastSuccessAt ? `Last success ${formatDateTime(row.sync.lastSuccessAt)}` : "Never synced"}
            </div>
            {row.sync.consecutiveFailures > 0 && (
              <div className="small" style={{ color: "var(--danger)" }}>
                {row.sync.consecutiveFailures} consecutive failure(s): {row.sync.lastError}
              </div>
            )}
          </span>
        ) : (
          <span className="muted small">Not started</span>
        ),
    },
    {
      key: "smtp",
      header: "Outbound SMTP",
      render: (row) =>
        row.smtpProfileName ?? <span className="small" style={{ color: "var(--warning)" }}>Not linked</span>,
    },
    {
      key: "credentials",
      header: "Credentials",
      render: (row) => (
        <span className="row" style={{ gap: 4 }}>
          <Badge tone={row.hasSecret ? "success" : "neutral"}>{row.hasSecret ? "Stored" : "None"}</Badge>
          {row.hasOauthToken && <Badge tone="neutral">OAuth</Badge>}
        </span>
      ),
    },
    {
      key: "actions",
      header: "Actions",
      align: "right",
      render: (row) => (
        <div className="row" style={{ justifyContent: "flex-end", gap: 6 }}>
          {canManage && (
            <>
              <button
                type="button"
                className="btn btn--sm"
                disabled={test.isPending}
                onClick={() => test.mutate(row.id)}
              >
                Test
              </button>
              <button
                type="button"
                className="btn btn--sm"
                disabled={row.status !== "ACTIVE" || sync.isPending}
                onClick={() => sync.mutate(row.id)}
              >
                Sync now
              </button>
              {row.status === "ACTIVE" ? (
                <button
                  type="button"
                  className="btn btn--sm btn--danger"
                  disabled={setStatus.isPending}
                  onClick={() => setStatus.mutate({ id: row.id, enabled: false })}
                >
                  Disable
                </button>
              ) : (
                <button
                  type="button"
                  className="btn btn--sm btn--primary"
                  disabled={setStatus.isPending}
                  onClick={() => setStatus.mutate({ id: row.id, enabled: true })}
                >
                  Enable
                </button>
              )}
            </>
          )}
        </div>
      ),
    },
  ];

  const update = (key: keyof FormState, value: string) => setForm({ ...form, [key]: value });

  return (
    <>
      <PageHeader
        title="Mailbox connections"
        subtitle="Inbound reading is separate from outbound sending: an SMTP profile cannot read mail, and a mailbox must pass a live connection test before it is enabled."
        actions={
          canManage ? (
            <button type="button" className="btn btn--primary" onClick={() => setCreating(true)}>
              Connect a mailbox
            </button>
          ) : null
        }
      />

      <Card padded={false}>
        {accounts.isPending && <TableSkeleton rows={4} />}
        {accounts.isError && (
          <ErrorState
            message={accounts.error instanceof ApiError ? accounts.error.message : "Could not load mailboxes."}
            onRetry={() => accounts.refetch()}
          />
        )}
        {accounts.isSuccess && accounts.data.length === 0 && (
          <EmptyState
            title="No mailboxes connected"
            description="Connect a real IMAP mailbox to receive customer replies. Nothing is simulated: until a mailbox is active, the inbox stays empty."
          />
        )}
        {accounts.isSuccess && accounts.data.length > 0 && (
          <DataTable columns={columns} rows={accounts.data} caption="Mailbox connections" />
        )}
      </Card>

      {creating && (
        <Modal
          title="Connect a mailbox"
          onClose={() => setCreating(false)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setCreating(false)}>
                Cancel
              </button>
              <button
                type="button"
                className="btn btn--primary"
                disabled={
                  create.isPending ||
                  form.name.trim().length < 2 ||
                  !form.mailboxAddress.includes("@") ||
                  form.secret.length < 1
                }
                onClick={() => create.mutate()}
              >
                Save and test later
              </button>
            </>
          }
        >
          <div className="grid grid--2">
            <Field label="Display name" htmlFor="mb-name">
              <input id="mb-name" value={form.name} onChange={(event) => update("name", event.target.value)} />
            </Field>
            <Field label="Provider" htmlFor="mb-provider" hint="Only IMAP is implemented in this release.">
              <select
                id="mb-provider"
                value={form.provider}
                onChange={(event) => update("provider", event.target.value)}
              >
                {PROVIDERS.map((provider) => (
                  <option key={provider} value={provider}>
                    {provider}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Mailbox address" htmlFor="mb-address">
              <input
                id="mb-address"
                type="email"
                value={form.mailboxAddress}
                onChange={(event) => update("mailboxAddress", event.target.value)}
              />
            </Field>
            <Field label="From name" htmlFor="mb-from">
              <input id="mb-from" value={form.fromName} onChange={(event) => update("fromName", event.target.value)} />
            </Field>
            <Field label="IMAP host" htmlFor="mb-host">
              <input id="mb-host" value={form.host} onChange={(event) => update("host", event.target.value)} />
            </Field>
            <Field label="Port" htmlFor="mb-port">
              <input
                id="mb-port"
                type="number"
                value={form.port}
                onChange={(event) => update("port", event.target.value)}
              />
            </Field>
            <Field label="TLS mode" htmlFor="mb-tls">
              <select id="mb-tls" value={form.tlsMode} onChange={(event) => update("tlsMode", event.target.value)}>
                <option value="IMPLICIT_TLS">IMPLICIT_TLS (993)</option>
                <option value="STARTTLS">STARTTLS (143)</option>
                <option value="NONE">NONE (not recommended)</option>
              </select>
            </Field>
            <Field label="Folder" htmlFor="mb-folder">
              <input id="mb-folder" value={form.folder} onChange={(event) => update("folder", event.target.value)} />
            </Field>
            <Field label="Username" htmlFor="mb-user" hint="Defaults to the mailbox address.">
              <input id="mb-user" value={form.username} onChange={(event) => update("username", event.target.value)} />
            </Field>
            <Field label="Password / app password" htmlFor="mb-secret" hint="Encrypted immediately; never returned.">
              <input
                id="mb-secret"
                type="password"
                autoComplete="new-password"
                value={form.secret}
                onChange={(event) => update("secret", event.target.value)}
              />
            </Field>
            <Field label="Outbound SMTP profile" htmlFor="mb-smtp" hint="Required before replies can be sent.">
              <select
                id="mb-smtp"
                value={form.smtpProfileId}
                onChange={(event) => update("smtpProfileId", event.target.value)}
              >
                <option value="">Link later…</option>
                {(smtpProfiles.data ?? []).map((profile) => (
                  <option key={profile.id} value={profile.id}>
                    {profile.name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Poll interval (seconds)" htmlFor="mb-poll">
              <input
                id="mb-poll"
                type="number"
                min={60}
                value={form.pollIntervalSeconds}
                onChange={(event) => update("pollIntervalSeconds", event.target.value)}
              />
            </Field>
          </div>
        </Modal>
      )}
    </>
  );
}
