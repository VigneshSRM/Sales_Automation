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
import type { TemplateView } from "@/lib/types";

/**
 * Email templates.
 *
 * Lifecycle is authoritative on the server: editing an APPROVED template reverts it to DRAFT
 * with a version bump, and approval rejects the author (four-eyes). The UI only offers the
 * transitions the server will accept, and always shows the server's response.
 */

const PURPOSES = ["OUTREACH", "FOLLOW_UP", "MEETING", "PROPOSAL", "NURTURE", "RENEWAL"];

type Draft = { id: string | null; name: string; purpose: string; subject: string; body: string; rowVersion: number };

const EMPTY: Draft = { id: null, name: "", purpose: "OUTREACH", subject: "", body: "", rowVersion: 0 };

export default function TemplatesPage() {
  const queryClient = useQueryClient();
  const toast = useToast();
  const canManage = useCapability("templates.manage");
  const [draft, setDraft] = useState<Draft | null>(null);
  const [preview, setPreview] = useState<{ subject: string; body: string; fields: string[] } | null>(null);

  const templates = useQuery({ queryKey: ["templates"], queryFn: api.templates });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["templates"] });

  const save = useMutation({
    mutationFn: (value: Draft) =>
      value.id
        ? api.updateTemplate(value.id, {
            name: value.name,
            purpose: value.purpose,
            subject: value.subject,
            body: value.body,
            expectedRowVersion: value.rowVersion,
          })
        : api.createTemplate({ name: value.name, purpose: value.purpose, subject: value.subject, body: value.body }),
    onSuccess: () => {
      toast.push("Template saved as a draft", "success");
      setDraft(null);
      invalidate();
    },
    onError: (error) =>
      toast.push(error instanceof ApiError ? error.message : "Could not save the template", "error"),
  });

  const submit = useMutation({
    mutationFn: (id: string) => api.submitTemplate(id),
    onSuccess: () => {
      toast.push("Submitted for approval", "success");
      invalidate();
    },
    onError: (error) => toast.push(error instanceof ApiError ? error.message : "Submit failed", "error"),
  });

  const approve = useMutation({
    mutationFn: (id: string) => api.approveTemplate(id),
    onSuccess: () => {
      toast.push("Template approved", "success");
      invalidate();
    },
    onError: (error) => toast.push(error instanceof ApiError ? error.message : "Approval failed", "error"),
  });

  const runPreview = useMutation({
    mutationFn: (value: Draft) => api.previewTemplate(value.subject, value.body),
    onSuccess: (result) =>
      setPreview({
        subject: result.subject,
        body: result.body,
        fields: result.mergeFields.map(
          (field, index) => `${field} → ${result.sampleValues[index] ?? "(empty)"}`,
        ),
      }),
    onError: (error) =>
      toast.push(error instanceof ApiError ? error.message : "Preview failed", "error"),
  });

  const columns: Column<TemplateView>[] = [
    {
      key: "name",
      header: "Template",
      render: (row) => (
        <span>
          <button
            type="button"
            className="link-strong"
            disabled={!canManage}
            onClick={() =>
              setDraft({
                id: row.id,
                name: row.name,
                purpose: row.purpose ?? "OUTREACH",
                subject: row.subject,
                body: row.body,
                rowVersion: row.rowVersion,
              })
            }
          >
            {row.name}
          </button>
          <div className="small muted">v{row.version} · {titleCase(row.purpose ?? "general")}</div>
        </span>
      ),
    },
    { key: "subject", header: "Subject", render: (row) => row.subject },
    { key: "status", header: "Status", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "fields",
      header: "Merge fields",
      render: (row) =>
        row.mergeFields.length === 0 ? (
          <span className="muted">none</span>
        ) : (
          <span className="small mono">{row.mergeFields.join(", ")}</span>
        ),
    },
    {
      key: "updatedAt",
      header: "Updated",
      render: (row) => <span className="small muted">{formatDateTime(row.updatedAt)}</span>,
    },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) => (
        <div className="row" style={{ justifyContent: "flex-end", gap: 6 }}>
          {row.status === "DRAFT" && (
            <button
              type="button"
              className="btn btn--sm"
              disabled={submit.isPending}
              onClick={() => submit.mutate(row.id)}
            >
              Submit
            </button>
          )}
          {row.status === "PENDING_APPROVAL" && canManage && (
            <button
              type="button"
              className="btn btn--sm btn--primary"
              disabled={approve.isPending}
              onClick={() => approve.mutate(row.id)}
            >
              Approve
            </button>
          )}
        </div>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Email templates"
        subtitle="Merge fields are restricted to an allow-list; unknown fields fail rendering instead of sending a broken message."
        actions={
          canManage ? (
            <button type="button" className="btn btn--primary btn--sm" onClick={() => setDraft({ ...EMPTY })}>
              New template
            </button>
          ) : null
        }
      />

      <Card padded={false}>
        {templates.isPending && <TableSkeleton rows={5} />}
        {templates.isError && (
          <ErrorState
            message={templates.error instanceof ApiError ? templates.error.message : "Could not load templates."}
            onRetry={() => templates.refetch()}
          />
        )}
        {templates.isSuccess && templates.data.length === 0 && (
          <EmptyState
            title="No templates yet"
            description="Create a template, submit it for approval, then compose outreach that references it."
          />
        )}
        {templates.isSuccess && templates.data.length > 0 && (
          <DataTable columns={columns} rows={templates.data} caption="Email templates" />
        )}
      </Card>

      {draft && (
        <Modal
          title={draft.id ? "Edit template" : "New template"}
          onClose={() => setDraft(null)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setDraft(null)}>
                Discard
              </button>
              <button
                type="button"
                className="btn"
                disabled={runPreview.isPending || !draft.subject || !draft.body}
                onClick={() => runPreview.mutate(draft)}
              >
                Preview
              </button>
              <button
                type="button"
                className="btn btn--primary"
                disabled={save.isPending || !draft.name || !draft.subject || !draft.body}
                onClick={() => save.mutate(draft)}
              >
                Save draft
              </button>
            </>
          }
        >
          <div className="stack">
            <div className="form-grid">
              <Field label="Name" htmlFor="tpl-name">
                <input
                  id="tpl-name"
                  maxLength={160}
                  value={draft.name}
                  onChange={(event) => setDraft({ ...draft, name: event.target.value })}
                />
              </Field>
              <Field label="Purpose" htmlFor="tpl-purpose">
                <select
                  id="tpl-purpose"
                  value={draft.purpose}
                  onChange={(event) => setDraft({ ...draft, purpose: event.target.value })}
                >
                  {PURPOSES.map((purpose) => (
                    <option key={purpose} value={purpose}>
                      {titleCase(purpose)}
                    </option>
                  ))}
                </select>
              </Field>
            </div>
            <Field label="Subject" htmlFor="tpl-subject" hint="Supports {{contact.firstName}} style fields.">
              <input
                id="tpl-subject"
                maxLength={300}
                value={draft.subject}
                onChange={(event) => setDraft({ ...draft, subject: event.target.value })}
              />
            </Field>
            <Field label="Body (HTML)" htmlFor="tpl-body" hint="Only allow-listed merge fields render.">
              <textarea
                id="tpl-body"
                rows={10}
                value={draft.body}
                onChange={(event) => setDraft({ ...draft, body: event.target.value })}
              />
            </Field>
            {draft.id && (
              <p className="small muted">
                Saving an APPROVED template reverts it to DRAFT and increments the version.
              </p>
            )}
          </div>
        </Modal>
      )}

      {preview && (
        <Modal
          title="Rendered preview"
          onClose={() => setPreview(null)}
          footer={
            <button type="button" className="btn" onClick={() => setPreview(null)}>
              Close
            </button>
          }
        >
          <div className="stack">
            <p className="small muted">
              Sample values come from the server resolver so the preview matches real rendering.
            </p>
            <p>
              <strong>{preview.subject}</strong>
            </p>
            <div className="template-preview" dangerouslySetInnerHTML={{ __html: preview.body }} />
            {preview.fields.length > 0 && (
              <div className="small mono muted">{preview.fields.join(" · ")}</div>
            )}
          </div>
        </Modal>
      )}
    </>
  );
}
