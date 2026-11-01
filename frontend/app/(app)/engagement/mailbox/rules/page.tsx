"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import { formatDateTime, titleCase } from "@/lib/format";
import {
  Badge, Card, DataTable, EmptyState, ErrorState, Field, Modal, PageHeader, TableSkeleton,
  type Column,
} from "@/components/ui";
import { useToast } from "@/components/toast";
import { useCapability } from "@/lib/hooks";
import type { MailboxRuleView } from "@/lib/types";

/**
 * Response policy (addendum 3.4).
 *
 * The rules are evaluated in priority order. NEVER_AUTO intents (unsubscribe, bounce, out-of-office,
 * spam) are hard-blocked in the backend and cannot be re-enabled from this screen, so a misconfigured
 * rule cannot turn the system into an auto-replier to bounces.
 */

const MODES = ["AUTOMATIC", "APPROVAL_REQUIRED", "HUMAN", "NO_RESPONSE"] as const;

const INTENTS = [
  "PRICING_ENQUIRY", "SERVICE_ENQUIRY", "TECHNICAL_QUESTION", "MEETING_REQUEST", "PROPOSAL_REQUEST",
  "OBJECTION", "NEGOTIATION", "CUSTOMER_SUPPORT", "COMPLAINT", "GENERAL_RESPONSE",
] as const;

export default function MailboxRulesPage() {
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [mode, setMode] = useState<string>("APPROVAL_REQUIRED");
  const [priority, setPriority] = useState("100");
  const [minConfidence, setMinConfidence] = useState("0.85");
  const [allowed, setAllowed] = useState<string[]>([]);
  const [blocked, setBlocked] = useState<string[]>([]);
  const [delaySeconds, setDelaySeconds] = useState("0");

  const queryClient = useQueryClient();
  const toast = useToast();
  const canManage = useCapability("email.rules.manage");

  const rules = useQuery({ queryKey: ["mailbox-rules"], queryFn: api.mailboxRules });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["mailbox-rules"] });
  const onError = (error: unknown, fallback: string) =>
    toast.push(error instanceof ApiError ? error.message : fallback, "error");

  const create = useMutation({
    mutationFn: () =>
      api.createMailboxRule({
        name,
        priority: Number(priority),
        mode,
        allowedIntents: allowed,
        blockedIntents: blocked,
        minConfidence: Number(minConfidence),
        businessDays: [],
        businessHoursStart: null,
        businessHoursEnd: null,
        delaySeconds: Number(delaySeconds),
        enabled: true,
      }),
    onSuccess: () => {
      toast.push("Response rule created", "success");
      setCreating(false);
      setName("");
      setAllowed([]);
      setBlocked([]);
      invalidate();
    },
    onError: (error) => onError(error, "Rule could not be created"),
  });

  const toggle = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) => api.setMailboxRuleEnabled(id, enabled),
    onSuccess: () => {
      toast.push("Rule updated", "success");
      invalidate();
    },
    onError: (error) => onError(error, "Rule could not be updated"),
  });

  const columns: Column<MailboxRuleView>[] = [
    { key: "priority", header: "Order", align: "right", render: (row) => String(row.priority) },
    {
      key: "name",
      header: "Rule",
      render: (row) => (
        <span>
          <strong>{row.name}</strong>
          <div className="small muted">
            min confidence {Math.round(row.minConfidence * 100)}%
            {row.delaySeconds > 0 ? ` · delay ${row.delaySeconds}s` : ""}
          </div>
        </span>
      ),
    },
    {
      key: "mode",
      header: "Mode",
      render: (row) => (
        <Badge
          tone={
            row.mode === "AUTOMATIC"
              ? "success"
              : row.mode === "APPROVAL_REQUIRED"
                ? "warning"
                : "neutral"
          }
        >
          {titleCase(row.mode)}
        </Badge>
      ),
    },
    {
      key: "intents",
      header: "Applies to",
      render: (row) => (
        <span className="small">
          {row.allowedIntents.length > 0 && <div>Allow: {row.allowedIntents.join(", ")}</div>}
          {row.blockedIntents.length > 0 && <div>Block: {row.blockedIntents.join(", ")}</div>}
          {row.allowedIntents.length === 0 && row.blockedIntents.length === 0 && (
            <span className="muted">Every intent</span>
          )}
        </span>
      ),
    },
    {
      key: "createdAt",
      header: "Created",
      render: (row) => <span className="small muted">{formatDateTime(row.createdAt)}</span>,
    },
    {
      key: "actions",
      header: "State",
      align: "right",
      render: (row) => (
        <div className="row" style={{ justifyContent: "flex-end", gap: 6 }}>
          <Badge tone={row.enabled ? "success" : "neutral"}>{row.enabled ? "Enabled" : "Disabled"}</Badge>
          {canManage && (
            <button
              type="button"
              className="btn btn--sm"
              disabled={toggle.isPending}
              onClick={() => toggle.mutate({ id: row.id, enabled: !row.enabled })}
            >
              {row.enabled ? "Disable" : "Enable"}
            </button>
          )}
        </div>
      ),
    },
  ];

  const toggleIntent = (intent: string, list: string[], setList: (next: string[]) => void) =>
    setList(list.includes(intent) ? list.filter((value) => value !== intent) : [...list, intent]);

  return (
    <>
      <PageHeader
        title="Response policy"
        subtitle="AI may only send automatically when a rule explicitly permits the intent, confidence and time window. Unsubscribe, bounce, spam and out-of-office messages are never answered."
        actions={
          canManage ? (
            <button type="button" className="btn btn--primary" onClick={() => setCreating(true)}>
              New rule
            </button>
          ) : null
        }
      />

      <Card padded={false}>
        {rules.isPending && <TableSkeleton rows={4} />}
        {rules.isError && (
          <ErrorState
            message={rules.error instanceof ApiError ? rules.error.message : "Could not load rules."}
            onRetry={() => rules.refetch()}
          />
        )}
        {rules.isSuccess && rules.data.length === 0 && (
          <EmptyState
            title="No rules configured"
            description="Without a rule, every AI draft goes to a human for approval. That is the safe default."
          />
        )}
        {rules.isSuccess && rules.data.length > 0 && (
          <DataTable columns={columns} rows={rules.data} caption="Response policy rules" />
        )}
      </Card>

      {creating && (
        <Modal
          title="New response rule"
          onClose={() => setCreating(false)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setCreating(false)}>
                Cancel
              </button>
              <button
                type="button"
                className="btn btn--primary"
                disabled={name.trim().length < 3 || create.isPending}
                onClick={() => create.mutate()}
              >
                Create rule
              </button>
            </>
          }
        >
          <div className="stack">
            <Field label="Rule name" htmlFor="rule-name">
              <input id="rule-name" value={name} onChange={(event) => setName(event.target.value)} />
            </Field>
            <div className="grid grid--2">
              <Field label="Mode" htmlFor="rule-mode">
                <select id="rule-mode" value={mode} onChange={(event) => setMode(event.target.value)}>
                  {MODES.map((option) => (
                    <option key={option} value={option}>
                      {titleCase(option)}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Priority (lower runs first)" htmlFor="rule-priority">
                <input
                  id="rule-priority"
                  type="number"
                  min={1}
                  max={1000}
                  value={priority}
                  onChange={(event) => setPriority(event.target.value)}
                />
              </Field>
              <Field label="Minimum confidence (0–1)" htmlFor="rule-confidence">
                <input
                  id="rule-confidence"
                  type="number"
                  step="0.01"
                  min={0}
                  max={1}
                  value={minConfidence}
                  onChange={(event) => setMinConfidence(event.target.value)}
                />
              </Field>
              <Field label="Delay before sending (seconds)" htmlFor="rule-delay">
                <input
                  id="rule-delay"
                  type="number"
                  min={0}
                  max={86400}
                  value={delaySeconds}
                  onChange={(event) => setDelaySeconds(event.target.value)}
                />
              </Field>
            </div>
            <Field
              label="Intents this rule may act on"
              hint="Leave both lists empty to apply to every intent not hard-blocked by policy."
            >
              <div className="row" style={{ flexWrap: "wrap", gap: 8 }}>
                {INTENTS.map((intent) => (
                  <label key={intent} className="small row" style={{ gap: 4 }}>
                    <input
                      type="checkbox"
                      checked={allowed.includes(intent)}
                      onChange={() => toggleIntent(intent, allowed, setAllowed)}
                    />
                    {titleCase(intent)}
                  </label>
                ))}
              </div>
            </Field>
            <Field label="Intents to block">
              <div className="row" style={{ flexWrap: "wrap", gap: 8 }}>
                {INTENTS.map((intent) => (
                  <label key={intent} className="small row" style={{ gap: 4 }}>
                    <input
                      type="checkbox"
                      checked={blocked.includes(intent)}
                      onChange={() => toggleIntent(intent, blocked, setBlocked)}
                    />
                    {titleCase(intent)}
                  </label>
                ))}
              </div>
            </Field>
            {mode === "AUTOMATIC" && (
              <p className="banner banner--warning small">
                Automatic mode lets the engine send without a human. Pricing, negotiation, objection,
                complaint, unsubscribe, bounce and out-of-office intents are still blocked by the backend.
              </p>
            )}
          </div>
        </Modal>
      )}
    </>
  );
}
