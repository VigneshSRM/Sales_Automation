"use client";

import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import { formatDateTime, formatMoney, titleCase } from "@/lib/format";
import { useSearchParamState } from "@/lib/hooks";
import {
  BackLink, Card, EmptyState, ErrorState, Field, Modal, PageHeader, StatusBadge, Tabs, TableSkeleton,
} from "@/components/ui";
import { useToast } from "@/components/toast";
import type { LeadDetail, LeadStatus } from "@/lib/types";

export default function LeadDetailPage() {
  const [id] = useSearchParamState("id");
  const [tab, setTab] = useState("overview");
  const [transitionTarget, setTransitionTarget] = useState<LeadStatus | null>(null);
  const [reason, setReason] = useState("");
  const [assignOpen, setAssignOpen] = useState(false);
  const [assignee, setAssignee] = useState("");
  const queryClient = useQueryClient();
  const toast = useToast();

  const lead = useQuery({
    queryKey: ["lead", id],
    queryFn: () => api.lead(id as string),
    enabled: Boolean(id),
  });

  const users = useQuery({
    queryKey: ["users-lookup"],
    queryFn: () => api.users({ page: 0, size: 100 }),
    enabled: assignOpen,
  });

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ["lead", id] });
    queryClient.invalidateQueries({ queryKey: ["timeline", id] });
    queryClient.invalidateQueries({ queryKey: ["dashboard"] });
    queryClient.invalidateQueries({ queryKey: ["leads"] });
  };

  const transition = useMutation({
    mutationFn: (target: LeadStatus) =>
      api.transitionLead(id as string, {
        targetStatus: target,
        reason: reason.trim() || null,
        expectedRowVersion: lead.data?.rowVersion ?? null,
      }),
    onSuccess: () => {
      toast.push("Lead status updated", "success");
      setTransitionTarget(null);
      setReason("");
      refresh();
    },
    onError: (error) => toast.push(error instanceof ApiError ? error.message : "Transition failed", "error"),
  });

  const assign = useMutation({
    mutationFn: (ownerUserId: string) =>
      api.assignLead(id as string, { ownerUserId, reason: "Reassigned from lead workspace" }),
    onSuccess: () => {
      toast.push("Owner updated", "success");
      setAssignOpen(false);
      refresh();
    },
    onError: (error) => toast.push(error instanceof ApiError ? error.message : "Assignment failed", "error"),
  });

  const qualify = useMutation({
    mutationFn: () => api.qualifyLead(id as string, {}),
    onSuccess: (updated) => {
      toast.push(`Score recalculated: ${updated.score ?? 0}/100`, "success");
      queryClient.setQueryData(["lead", id], updated);
      refresh();
    },
    onError: (error) => toast.push(error instanceof ApiError ? error.message : "Scoring failed", "error"),
  });

  if (!id) {
    return <ErrorState title="No lead selected" message="Open a lead from the list to see its workspace." />;
  }
  if (lead.isPending) return <TableSkeleton rows={8} />;
  if (lead.isError) return <ErrorState message={(lead.error as Error).message} onRetry={() => lead.refetch()} />;

  const data = lead.data;

  return (
    <>
      <BackLink href="/leads">All leads</BackLink>
      <PageHeader
        title={`${data.firstName} ${data.lastName ?? ""}`.trim()}
        subtitle={`${data.companyName}${data.location ? ` · ${data.location}` : ""} · updated ${formatDateTime(data.updatedAt)}`}
        actions={
          <>
            <button type="button" className="btn btn--sm" disabled={qualify.isPending}
              onClick={() => qualify.mutate()}>
              {qualify.isPending ? "Scoring…" : "Recalculate score"}
            </button>
            <button type="button" className="btn btn--sm" onClick={() => setAssignOpen(true)}>
              {data.ownerName ? "Reassign" : "Assign owner"}
            </button>
            {data.allowedTransitions.length > 0 && (
              <select className="btn btn--primary btn--sm" aria-label="Move lead to status"
                value="" onChange={(event) => event.target.value && setTransitionTarget(event.target.value as LeadStatus)}>
                <option value="">Change status…</option>
                {data.allowedTransitions.map((status) => (
                  <option key={status} value={status}>{titleCase(status)}</option>
                ))}
              </select>
            )}
          </>
        }
      />

      <div className="row" style={{ marginBottom: 14, flexWrap: "wrap" }}>
        <StatusBadge status={data.status} />
        <span className="badge badge--neutral">
          Score {data.score ?? "—"}{data.scoreBand ? ` · ${titleCase(data.scoreBand)}` : ""}
        </span>
        <span className="badge badge--neutral">{data.sourceCode ?? "No source"}</span>
        {data.duplicates.length > 0 && (
          <span className="badge badge--danger">{data.duplicates.length} duplicate candidate(s)</span>
        )}
        <span className="small muted mono">v{data.rowVersion}</span>
      </div>

      <Tabs tabs={[
        { key: "overview", label: "Overview" },
        { key: "score", label: "Score breakdown" },
        { key: "timeline", label: "Timeline" },
        { key: "duplicates", label: "Duplicates", count: data.duplicates.length },
      ]} active={tab} onChange={setTab} />

      {tab === "overview" && <Overview lead={data} />}
      {tab === "score" && <ScorePanel lead={data} />}
      {tab === "timeline" && <Timeline leadId={data.id} />}
      {tab === "duplicates" && <Duplicates lead={data} onMerged={refresh} />}

      {transitionTarget && (
        <Modal title={`Move to ${titleCase(transitionTarget)}`} onClose={() => setTransitionTarget(null)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setTransitionTarget(null)}>Cancel</button>
              <button type="button" className="btn btn--primary" disabled={transition.isPending}
                onClick={() => transition.mutate(transitionTarget)}>
                {transition.isPending ? "Saving…" : "Confirm transition"}
              </button>
            </>
          }>
          <div className="stack">
            <div className="banner banner--info">
              Allowed transitions are computed by the server from the lead lifecycle graph; disallowed
              moves are rejected even if they are submitted directly.
            </div>
            <Field label="Reason (recorded in the audit trail)" htmlFor="transition-reason">
              <textarea id="transition-reason" value={reason} onChange={(event) => setReason(event.target.value)} />
            </Field>
          </div>
        </Modal>
      )}

      {assignOpen && (
        <Modal title="Assign owner" onClose={() => setAssignOpen(false)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setAssignOpen(false)}>Cancel</button>
              <button type="button" className="btn btn--primary" disabled={!assignee || assign.isPending}
                onClick={() => assign.mutate(assignee)}>Assign</button>
            </>
          }>
          {users.isPending && <p className="small muted">Loading users…</p>}
          {users.isError && <p className="small" role="alert">Users could not be loaded.</p>}
          <Field label="Owner" htmlFor="assignee" hint="Only active users in this workspace are listed.">
            <select id="assignee" value={assignee} onChange={(event) => setAssignee(event.target.value)}>
              <option value="">Select a user…</option>
              {(users.data?.items ?? []).filter((user) => user.status === "ACTIVE").map((user) => (
                <option key={user.id} value={user.id}>{user.displayName} · {user.email}</option>
              ))}
            </select>
          </Field>
        </Modal>
      )}
    </>
  );
}

function Overview({ lead }: { lead: LeadDetail }) {
  return (
    <div className="grid grid--2">
      <Card title="Lead details">
        <dl className="definition-list">
          <dt>Company</dt><dd>{lead.companyName}</dd>
          <dt>Email</dt><dd>{lead.email ?? "—"}</dd>
          <dt>Phone</dt><dd>{lead.phone ?? "—"}</dd>
          <dt>Website</dt><dd>{lead.website ?? "—"}</dd>
          <dt>Requirement</dt><dd>{lead.requirement ?? "—"}</dd>
          <dt>Budget</dt><dd>{formatMoney(lead.budgetAmount, lead.currency)}</dd>
          <dt>Timeline</dt><dd>{lead.timelineCode ? titleCase(lead.timelineCode) : "Unknown"}</dd>
          <dt>Industry</dt><dd>{lead.industryCode ?? "—"}</dd>
          <dt>Owner</dt><dd>{lead.ownerName ?? "Unassigned"}</dd>
          <dt>Consent reference</dt><dd>{lead.consentReference ?? "Not recorded"}</dd>
          <dt>Created</dt><dd>{formatDateTime(lead.createdAt)}</dd>
        </dl>
      </Card>
      <div className="stack">
        <Card title="Services of interest">
          {lead.serviceIds.length === 0 ? (
            <p className="small muted">No services selected.</p>
          ) : (
            <ul className="small" style={{ margin: 0, paddingLeft: 16 }}>
              {lead.serviceIds.map((serviceId) => <li key={serviceId} className="mono">{serviceId}</li>)}
            </ul>
          )}
        </Card>
        <Card title="Next steps">
          <ul className="small muted" style={{ margin: 0, paddingLeft: 16 }}>
            <li>Capture discovery notes on the timeline after the first call.</li>
            <li>Convert to an opportunity once discovery is complete.</li>
            <li>Outbound email requires a consent reference and an active SMTP profile.</li>
          </ul>
        </Card>
      </div>
    </div>
  );
}

function ScorePanel({ lead }: { lead: LeadDetail }) {
  return (
    <Card title={`Score · model v${lead.latestScore?.modelVersion ?? "—"}`}>
      {!lead.latestScore ? (
        <EmptyState title="Not scored yet"
          description="Run qualification to score this lead against the active scoring model." />
      ) : (
        <>
          <div className="row row--between" style={{ marginBottom: 12 }}>
            <div>
              <div style={{ fontSize: 30, fontWeight: 650 }}>{lead.latestScore.total}<span className="kpi__unit">/100</span></div>
              <div className="small muted">Band: {titleCase(lead.latestScore.band)} · calculated {formatDateTime(lead.latestScore.calculatedAt)}</div>
            </div>
          </div>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">Factor</th>
                  <th scope="col" style={{ textAlign: "right" }}>Weight</th>
                  <th scope="col" style={{ textAlign: "right" }}>Fit</th>
                  <th scope="col" style={{ textAlign: "right" }}>Points</th>
                  <th scope="col">Evidence</th>
                </tr>
              </thead>
              <tbody>
                {lead.latestScore.factors.map((factor) => (
                  <tr key={factor.code}>
                    <td>{titleCase(factor.code)}</td>
                    <td style={{ textAlign: "right" }}>{factor.weight}</td>
                    <td style={{ textAlign: "right" }}>{factor.fit}%</td>
                    <td style={{ textAlign: "right" }}><strong>{factor.points}</strong></td>
                    <td className="small muted">{factor.evidence}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="small muted" style={{ marginTop: 10 }}>
            Weights are versioned per workspace. Overrides require a reason and are audited.
          </p>
        </>
      )}
    </Card>
  );
}

function Timeline({ leadId }: { leadId: string }) {
  const timeline = useQuery({
    queryKey: ["timeline", leadId],
    queryFn: () => api.timeline("LEAD", leadId, 0, 50),
  });

  if (timeline.isPending) return <TableSkeleton rows={4} />;
  if (timeline.isError) return <ErrorState message={(timeline.error as Error).message} onRetry={() => timeline.refetch()} />;
  if (timeline.data.items.length === 0) {
    return <EmptyState title="No activity yet"
      description="Calls, notes, task events and status changes appear here as they happen." />;
  }

  return (
    <Card title="Activity timeline">
      <div className="timeline">
        {timeline.data.items.map((entry) => (
          <div className="timeline__item" key={entry.id}>
            <div className="timeline__rail">
              <span className="timeline__dot" aria-hidden="true" />
              <span className="timeline__line" aria-hidden="true" />
            </div>
            <div>
              <div className="row" style={{ gap: 8, flexWrap: "wrap" }}>
                <span className="badge badge--neutral">{titleCase(entry.type)}</span>
                <strong style={{ fontSize: 13 }}>{entry.subject ?? titleCase(entry.type)}</strong>
                <span className="small muted" style={{ marginLeft: "auto" }}>{formatDateTime(entry.occurredAt)}</span>
              </div>
              {entry.body && <p className="small" style={{ marginTop: 4 }}>{entry.body}</p>}
              <p className="small muted">by {entry.actorName ?? "System"}</p>
            </div>
          </div>
        ))}
      </div>
    </Card>
  );
}

function Duplicates({ lead, onMerged }: { lead: LeadDetail; onMerged: () => void }) {
  const toast = useToast();
  const [merging, setMerging] = useState<string | null>(null);

  const merge = useMutation({
    mutationFn: (sourceId: string) =>
      api.mergeLeads({ targetLeadId: lead.id, sourceLeadId: sourceId, reason: "Confirmed duplicate" }),
    onSuccess: () => {
      toast.push("Leads merged", "success");
      setMerging(null);
      onMerged();
    },
    onError: () => toast.push("Merge failed — check permissions and record versions", "error"),
  });

  if (lead.duplicates.length === 0) {
    return <EmptyState title="No duplicate candidates"
      description="Duplicate detection runs on every capture and update: exact email/phone matches are strong, domain+company matches are candidates, fuzzy name matches are warnings." />;
  }

  return (
    <Card title="Duplicate candidates" padded={false}>
      <div className="card__body small muted">
        Nothing is merged automatically. Merging requires a privileged role and writes a merge audit record.
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">Record</th>
              <th scope="col">Match</th>
              <th scope="col" style={{ textAlign: "right" }}>Confidence</th>
              <th scope="col" />
            </tr>
          </thead>
          <tbody>
            {lead.duplicates.map((candidate) => (
              <tr key={candidate.candidateId}>
                <td>
                  <strong>{candidate.display}</strong>
                  <div className="small muted">{candidate.detail}</div>
                </td>
                <td><span className="badge badge--warning">{candidate.matchType.replaceAll("_", " ")}</span></td>
                <td style={{ textAlign: "right" }}>{Math.round(candidate.confidence * 100)}%</td>
                <td style={{ textAlign: "right" }}>
                  {candidate.candidateType === "LEAD" ? (
                    <>
                      <Link className="btn btn--sm" href={`/leads/detail?id=${candidate.candidateId}`}>Open</Link>{" "}
                      {merging === candidate.candidateId ? (
                        <span className="row" style={{ gap: 6, justifyContent: "flex-end", marginTop: 6 }}>
                          <button type="button" className="btn btn--sm btn--danger"
                            onClick={() => merge.mutate(candidate.candidateId)}>Confirm merge</button>
                          <button type="button" className="btn btn--sm" onClick={() => setMerging(null)}>Cancel</button>
                        </span>
                      ) : (
                        <button type="button" className="btn btn--sm"
                          onClick={() => setMerging(candidate.candidateId)}>Merge into this</button>
                      )}
                    </>
                  ) : (
                    <span className="small muted">Review manually</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  );
}
