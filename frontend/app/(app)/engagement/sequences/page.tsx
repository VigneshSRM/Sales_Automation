"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import { formatDateTime, titleCase } from "@/lib/format";
import {
  Card, DataTable, EmptyState, ErrorState, Field, Modal, PageHeader, Pagination, StatusBadge,
  TableSkeleton, Tabs, type Column,
} from "@/components/ui";
import { useToast } from "@/components/toast";
import { useCapability } from "@/lib/hooks";
import type { EnrollmentView, SequenceView } from "@/lib/types";

/**
 * Follow-up sequences.
 *
 * A sequence is versioned: activating freezes the current steps into a new version so that
 * enrolled leads keep the cadence they were enrolled under. Enrolment is refused by the
 * server for suppressed / disqualified leads and for duplicate active enrolments.
 */

type StepDraft = { dayOffset: number; templateId: string; subjectOverride: string; stopOnReply: boolean };

export default function SequencesPage() {
  const [tab, setTab] = useState("sequences");
  const [creating, setCreating] = useState(false);
  const [enrolling, setEnrolling] = useState<SequenceView | null>(null);
  const [enrollLeadId, setEnrollLeadId] = useState("");
  const [enrollSmtpId, setEnrollSmtpId] = useState("");
  const [page, setPage] = useState(0);

  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [steps, setSteps] = useState<StepDraft[]>([
    { dayOffset: 0, templateId: "", subjectOverride: "", stopOnReply: true },
  ]);

  const queryClient = useQueryClient();
  const toast = useToast();
  const canManage = useCapability("sequences.manage");

  const sequences = useQuery({ queryKey: ["sequences"], queryFn: api.sequences });
  const templates = useQuery({ queryKey: ["templates"], queryFn: api.templates });
  const smtp = useQuery({ queryKey: ["smtp-profiles"], queryFn: api.smtpProfiles });
  const enrollments = useQuery({
    queryKey: ["enrollments", page],
    queryFn: () => api.enrollments({ page, size: 25 }),
    enabled: tab === "enrollments",
  });
  const leads = useQuery({
    queryKey: ["leads", "sequence-picker"],
    queryFn: () => api.leads({ size: 100, sort: "fullName", direction: "asc" }),
    enabled: enrolling !== null,
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["sequences"] });
    queryClient.invalidateQueries({ queryKey: ["enrollments"] });
  };

  const create = useMutation({
    mutationFn: () =>
      api.createSequence({
        name,
        description: description || null,
        steps: steps
          .filter((step) => step.templateId)
          .map((step) => ({
            dayOffset: step.dayOffset,
            templateId: step.templateId,
            subjectOverride: step.subjectOverride || null,
            stopOnReply: step.stopOnReply,
          })),
      }),
    onSuccess: () => {
      toast.push("Sequence created as a draft", "success");
      setCreating(false);
      setName("");
      setDescription("");
      setSteps([{ dayOffset: 0, templateId: "", subjectOverride: "", stopOnReply: true }]);
      invalidate();
    },
    onError: (error) =>
      toast.push(error instanceof ApiError ? error.message : "Could not create the sequence", "error"),
  });

  const activate = useMutation({
    mutationFn: (id: string) => api.activateSequence(id),
    onSuccess: () => {
      toast.push("Sequence activated — enrolments now use the new version", "success");
      invalidate();
    },
    onError: (error) => toast.push(error instanceof ApiError ? error.message : "Activation failed", "error"),
  });

  const pause = useMutation({
    mutationFn: (id: string) => api.pauseSequence(id),
    onSuccess: () => {
      toast.push("Sequence paused — no further steps will be queued", "success");
      invalidate();
    },
    onError: (error) => toast.push(error instanceof ApiError ? error.message : "Pause failed", "error"),
  });

  const enroll = useMutation({
    mutationFn: () => api.enrollLead(enrolling!.id, enrollLeadId, enrollSmtpId || undefined),
    onSuccess: () => {
      toast.push("Lead enrolled", "success");
      setEnrolling(null);
      setEnrollLeadId("");
      setEnrollSmtpId("");
      invalidate();
    },
    onError: (error) =>
      toast.push(error instanceof ApiError ? error.message : "Enrolment rejected", "error"),
  });

  const stop = useMutation({
    mutationFn: (id: string) => api.stopEnrollment(id, "MANUAL"),
    onSuccess: () => {
      toast.push("Enrolment stopped", "success");
      invalidate();
    },
    onError: (error) => toast.push(error instanceof ApiError ? error.message : "Stop failed", "error"),
  });

  const sequenceColumns: Column<SequenceView>[] = [
    {
      key: "name",
      header: "Sequence",
      render: (row) => (
        <span>
          <strong>{row.name}</strong>
          <div className="small muted">
            {row.description ?? "No description"} · v{row.activeVersion ?? "draft"}
          </div>
        </span>
      ),
    },
    { key: "status", header: "Status", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "steps",
      header: "Cadence",
      render: (row) =>
        row.steps.length === 0 ? (
          <span className="muted">no steps</span>
        ) : (
          <span className="small">
            {row.steps.map((step) => `Day ${step.dayOffset}`).join(" → ")}
          </span>
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
      render: (row) =>
        canManage ? (
          <div className="row" style={{ justifyContent: "flex-end", gap: 6 }}>
            {row.status !== "ACTIVE" ? (
              <button
                type="button"
                className="btn btn--sm btn--primary"
                disabled={activate.isPending || row.stepCount === 0}
                title={row.stepCount === 0 ? "Add at least one step before activating" : undefined}
                onClick={() => activate.mutate(row.id)}
              >
                Activate
              </button>
            ) : (
              <>
                <button type="button" className="btn btn--sm" disabled={pause.isPending}
                  onClick={() => pause.mutate(row.id)}>
                  Pause
                </button>
                <button type="button" className="btn btn--sm" onClick={() => setEnrolling(row)}>
                  Enrol lead
                </button>
              </>
            )}
          </div>
        ) : null,
    },
  ];

  const enrollmentColumns: Column<EnrollmentView>[] = [
    {
      key: "lead",
      header: "Lead",
      render: (row) => (
        <span>
          <strong>{row.leadName}</strong>
          <div className="small muted">{row.sequenceName}</div>
        </span>
      ),
    },
    { key: "status", header: "Status", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "progress",
      header: "Progress",
      render: (row) => (
        <span className="small">
          Step {row.currentStep} of {row.totalSteps}
        </span>
      ),
    },
    {
      key: "nextRunAt",
      header: "Next run",
      render: (row) =>
        row.nextRunAt ? formatDateTime(row.nextRunAt) : <span className="muted">—</span>,
    },
    {
      key: "stopReason",
      header: "Stop reason",
      render: (row) => (row.stopReason ? titleCase(row.stopReason) : <span className="muted">—</span>),
    },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) =>
        canManage && row.status === "ACTIVE" ? (
          <button type="button" className="btn btn--sm btn--danger" disabled={stop.isPending}
            onClick={() => stop.mutate(row.id)}>
            Stop
          </button>
        ) : null,
    },
  ];

  return (
    <>
      <PageHeader
        title="Follow-up sequences"
        subtitle="Versioned cadences. Replies, unsubscribes, bounces and bookings stop the sequence automatically."
        actions={
          canManage ? (
            <button type="button" className="btn btn--primary btn--sm" onClick={() => setCreating(true)}>
              New sequence
            </button>
          ) : null
        }
      />

      <Tabs
        tabs={[
          { key: "sequences", label: "Sequences", count: sequences.data?.length },
          { key: "enrollments", label: "Enrolments" },
        ]}
        active={tab}
        onChange={(key) => {
          setTab(key);
          setPage(0);
        }}
      />

      {tab === "sequences" && (
        <Card padded={false}>
          {sequences.isPending && <TableSkeleton rows={5} />}
          {sequences.isError && (
            <ErrorState
              message={sequences.error instanceof ApiError ? sequences.error.message : "Could not load sequences."}
              onRetry={() => sequences.refetch()}
            />
          )}
          {sequences.isSuccess && sequences.data.length === 0 && (
            <EmptyState
              title="No sequences yet"
              description="Create a cadence from approved templates (0, 2, 5, 8, 12 days, then nurture)."
            />
          )}
          {sequences.isSuccess && sequences.data.length > 0 && (
            <DataTable columns={sequenceColumns} rows={sequences.data} caption="Follow-up sequences" />
          )}
        </Card>
      )}

      {tab === "enrollments" && (
        <Card padded={false}>
          {enrollments.isPending && <TableSkeleton rows={5} />}
          {enrollments.isError && (
            <ErrorState
              message={enrollments.error instanceof ApiError ? enrollments.error.message : "Could not load enrolments."}
              onRetry={() => enrollments.refetch()}
            />
          )}
          {enrollments.isSuccess && enrollments.data.items.length === 0 && (
            <EmptyState title="No enrolments" description="Activate a sequence, then enrol a qualified lead." />
          )}
          {enrollments.isSuccess && enrollments.data.items.length > 0 && (
            <>
              <DataTable
                columns={enrollmentColumns}
                rows={enrollments.data.items}
                caption="Sequence enrolments"
              />
              <Pagination
                page={page}
                size={enrollments.data.size}
                totalElements={enrollments.data.totalElements}
                onPage={setPage}
              />
            </>
          )}
        </Card>
      )}

      {creating && (
        <Modal
          title="New sequence"
          onClose={() => setCreating(false)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setCreating(false)}>
                Discard
              </button>
              <button
                type="button"
                className="btn btn--primary"
                disabled={create.isPending || !name || steps.every((step) => !step.templateId)}
                onClick={() => create.mutate()}
              >
                Save draft
              </button>
            </>
          }
        >
          <div className="stack">
            <div className="form-grid">
              <Field label="Name" htmlFor="seq-name">
                <input
                  id="seq-name"
                  maxLength={160}
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                />
              </Field>
              <Field label="Description" htmlFor="seq-desc">
                <input
                  id="seq-desc"
                  maxLength={300}
                  value={description}
                  onChange={(event) => setDescription(event.target.value)}
                />
              </Field>
            </div>

            <p className="small muted">
              Steps use approved templates. Day offsets are days after enrolment; step 1 is usually day 0.
            </p>

            <div className="stack" style={{ gap: 8 }}>
              {steps.map((step, index) => (
                <div key={index} className="step-row">
                  <Field label="Day" htmlFor={`step-day-${index}`}>
                    <input
                      id={`step-day-${index}`}
                      type="number"
                      min={0}
                      max={365}
                      value={step.dayOffset}
                      onChange={(event) =>
                        setSteps(
                          steps.map((current, position) =>
                            position === index
                              ? { ...current, dayOffset: Number(event.target.value) }
                              : current,
                          ),
                        )
                      }
                    />
                  </Field>
                  <Field label="Template" htmlFor={`step-tpl-${index}`}>
                    <select
                      id={`step-tpl-${index}`}
                      value={step.templateId}
                      onChange={(event) =>
                        setSteps(
                          steps.map((current, position) =>
                            position === index ? { ...current, templateId: event.target.value } : current,
                          ),
                        )
                      }
                    >
                      <option value="">Select…</option>
                      {(templates.data ?? [])
                        .filter((template) => template.status === "APPROVED")
                        .map((template) => (
                          <option key={template.id} value={template.id}>
                            {template.name}
                          </option>
                        ))}
                    </select>
                  </Field>
                  <Field label="Subject override" htmlFor={`step-sub-${index}`}>
                    <input
                      id={`step-sub-${index}`}
                      maxLength={300}
                      value={step.subjectOverride}
                      onChange={(event) =>
                        setSteps(
                          steps.map((current, position) =>
                            position === index
                              ? { ...current, subjectOverride: event.target.value }
                              : current,
                          ),
                        )
                      }
                    />
                  </Field>
                  <Field label="Stop on reply" htmlFor={`step-stop-${index}`}>
                    <select
                      id={`step-stop-${index}`}
                      value={step.stopOnReply ? "yes" : "no"}
                      onChange={(event) =>
                        setSteps(
                          steps.map((current, position) =>
                            position === index
                              ? { ...current, stopOnReply: event.target.value === "yes" }
                              : current,
                          ),
                        )
                      }
                    >
                      <option value="yes">Yes</option>
                      <option value="no">No</option>
                    </select>
                  </Field>
                  <button
                    type="button"
                    className="btn btn--sm btn--ghost"
                    aria-label={`Remove step ${index + 1}`}
                    disabled={steps.length === 1}
                    onClick={() => setSteps(steps.filter((_, position) => position !== index))}
                  >
                    ✕
                  </button>
                </div>
              ))}
            </div>

            <div>
              <button
                type="button"
                className="btn btn--sm"
                onClick={() =>
                  setSteps([
                    ...steps,
                    {
                      dayOffset: (steps.at(-1)?.dayOffset ?? 0) + 2,
                      templateId: "",
                      subjectOverride: "",
                      stopOnReply: true,
                    },
                  ])
                }
              >
                Add step
              </button>
            </div>

            {(templates.data ?? []).filter((template) => template.status === "APPROVED").length === 0 && (
              <p className="small" role="note" style={{ color: "var(--warning)" }}>
                No approved templates yet — approve a template before a sequence can be activated.
              </p>
            )}
          </div>
        </Modal>
      )}

      {enrolling && (
        <Modal
          title={`Enrol a lead in ${enrolling.name}`}
          onClose={() => setEnrolling(null)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setEnrolling(null)}>
                Cancel
              </button>
              <button
                type="button"
                className="btn btn--primary"
                disabled={enroll.isPending || !enrollLeadId}
                onClick={() => enroll.mutate()}
              >
                Enrol
              </button>
            </>
          }
        >
          <div className="stack">
            <Field label="Lead" htmlFor="enroll-lead" hint="Only leads eligible for outreach are accepted.">
              <select
                id="enroll-lead"
                value={enrollLeadId}
                onChange={(event) => setEnrollLeadId(event.target.value)}
              >
                <option value="">Select a lead…</option>
                {(leads.data?.items ?? []).map((lead) => (
                  <option key={lead.id} value={lead.id}>
                    {lead.fullName} — {lead.companyName} ({titleCase(lead.status)})
                  </option>
                ))}
              </select>
            </Field>
            <Field
              label="SMTP account"
              htmlFor="enroll-smtp"
              hint="Optional: defaults to the profile the sequence was configured with."
            >
              <select
                id="enroll-smtp"
                value={enrollSmtpId}
                onChange={(event) => setEnrollSmtpId(event.target.value)}
              >
                <option value="">Use default</option>
                {(smtp.data ?? [])
                  .filter((profile) => profile.status === "ACTIVE")
                  .map((profile) => (
                    <option key={profile.id} value={profile.id}>
                      {profile.name} ({profile.sentToday}/{profile.dailyLimit} today)
                    </option>
                  ))}
              </select>
            </Field>
          </div>
        </Modal>
      )}
    </>
  );
}
