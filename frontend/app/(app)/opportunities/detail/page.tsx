"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import { formatDateTime, formatMoney, titleCase } from "@/lib/format";
import { useSearchParamState } from "@/lib/hooks";
import {
  BackLink, Card, ErrorState, Field, Modal, PageHeader, TableSkeleton,
} from "@/components/ui";
import { useToast } from "@/components/toast";
import type { StageRef } from "@/lib/types";

export default function OpportunityDetailPage() {
  const [id] = useSearchParamState("id");
  const [target, setTarget] = useState<StageRef | null>(null);
  const [reason, setReason] = useState("");
  const [closeDate, setCloseDate] = useState("");
  const [finalAmount, setFinalAmount] = useState("");
  const [decisionContactId, setDecisionContactId] = useState("");
  const [lossReasonCode, setLossReasonCode] = useState("");
  const queryClient = useQueryClient();
  const toast = useToast();

  const opportunity = useQuery({
    queryKey: ["opportunity", id],
    queryFn: () => api.opportunity(id as string),
    enabled: Boolean(id),
  });
  const lossReasons = useQuery({
    queryKey: ["loss-reasons"],
    queryFn: api.lossReasons,
    enabled: Boolean(target?.terminalType === "LOST"),
  });

  const transition = useMutation({
    mutationFn: (stage: StageRef) =>
      api.transitionOpportunity(id as string, {
        targetStageCode: stage.code,
        reason: reason.trim() || null,
        expectedRowVersion: opportunity.data?.rowVersion ?? null,
        closeDate: closeDate || null,
        finalAmount: finalAmount ? Number(finalAmount) : null,
        decisionContactId: decisionContactId || null,
        lossReasonCode: lossReasonCode || null,
      }),
    onSuccess: () => {
      toast.push("Stage updated", "success");
      setTarget(null);
      setReason("");
      queryClient.invalidateQueries({ queryKey: ["opportunity", id] });
      queryClient.invalidateQueries({ queryKey: ["pipeline"] });
      queryClient.invalidateQueries({ queryKey: ["dashboard"] });
    },
    onError: (error) => toast.push(
      error instanceof ApiError ? `${error.message}` : "Stage transition was rejected", "error"),
  });

  if (!id) return <ErrorState title="No opportunity selected" message="Open a deal from the pipeline board." />;
  if (opportunity.isPending) return <TableSkeleton rows={8} />;
  if (opportunity.isError) {
    return <ErrorState message={(opportunity.error as Error).message} onRetry={() => opportunity.refetch()} />;
  }
  const data = opportunity.data;
  const needsCloseDate = target?.terminalType === "WON";
  const needsLossReason = target?.terminalType === "LOST";

  return (
    <>
      <BackLink href="/pipeline">Pipeline</BackLink>
      <PageHeader title={data.name}
        subtitle={`${data.accountName ?? "No account"} · ${data.stage?.name ?? "No stage"} · updated ${formatDateTime(data.updatedAt)}`}
        actions={data.availableStages.length > 0 && (
          <select className="btn btn--primary btn--sm" aria-label="Move to stage" value=""
            onChange={(event) => {
              const stage = data.availableStages.find((candidate) => candidate.code === event.target.value);
              if (stage) { setTarget(stage); setFinalAmount(String(data.amount)); }
            }}>
            <option value="">Move stage…</option>
            {data.availableStages.map((stage) => (
              <option key={stage.code} value={stage.code}>{stage.name}</option>
            ))}
          </select>
        )} />

      <div className="row" style={{ flexWrap: "wrap", marginBottom: 14 }}>
        <span className="badge badge--neutral">{data.stage?.name ?? "No stage"}</span>
        <span className="badge">{formatMoney(data.amount, data.currency)}</span>
        <span className="badge badge--neutral">Weighted {formatMoney(data.weightedValue, data.currency)}</span>
        <span className="badge badge--neutral">{data.probability}% probability</span>
        <span className="small muted mono">v{data.rowVersion}</span>
      </div>

      <div className="grid grid--2">
        <Card title="Deal facts">
          <dl className="definition-list">
            <dt>Account</dt><dd>{data.accountName ?? "—"}</dd>
            <dt>Primary contact</dt><dd>{data.primaryContactName ?? "—"}</dd>
            <dt>Service</dt><dd>{data.serviceName ?? "—"}</dd>
            <dt>Owner</dt><dd>{data.ownerName ?? "Unassigned"}</dd>
            <dt>Expected close</dt><dd>{data.expectedCloseDate ?? "—"}</dd>
            <dt>Next step</dt><dd>{data.nextStep ?? "—"}</dd>
            <dt>Competitor</dt><dd>{data.competitor ?? "—"}</dd>
            {data.closeDate && <><dt>Closed on</dt><dd>{data.closeDate}</dd></>}
            {data.finalAmount != null && (
              <><dt>Final amount</dt><dd>{formatMoney(data.finalAmount, data.finalCurrency)}</dd></>
            )}
            {data.lossReasonCode && <><dt>Loss reason</dt><dd>{data.lossReasonCode}</dd></>}
          </dl>
        </Card>
        <Card title="Stage history">
          {data.history.length === 0 ? (
            <p className="small muted">No stage changes recorded yet.</p>
          ) : (
            <div className="timeline">
              {data.history.map((entry, index) => (
                <div className="timeline__item" key={`${entry.occurredAt}-${index}`}>
                  <div className="timeline__rail">
                    <span className="timeline__dot" aria-hidden="true" />
                    <span className="timeline__line" aria-hidden="true" />
                  </div>
                  <div>
                    <div className="row" style={{ gap: 8 }}>
                      <strong style={{ fontSize: 13 }}>
                        {entry.fromStage ? `${titleCase(entry.fromStage)} → ` : ""}{titleCase(entry.toStage ?? "")}
                      </strong>
                      <span className="small muted" style={{ marginLeft: "auto" }}>
                        {formatDateTime(entry.occurredAt)}
                      </span>
                    </div>
                    <div className="small muted">{entry.actorName} · rule set {entry.ruleVersion}</div>
                    {entry.reason && <p className="small">{entry.reason}</p>}
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>

      {target && (
        <Modal title={`Move to ${target.name}`} onClose={() => setTarget(null)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setTarget(null)}>Cancel</button>
              <button type="button" className="btn btn--primary" disabled={transition.isPending}
                onClick={() => transition.mutate(target)}>
                {transition.isPending ? "Saving…" : "Confirm"}
              </button>
            </>
          }>
          <div className="stack">
            {target.terminalType === "WON" && (
              <div className="banner banner--info">
                Closing as won requires a close date, the final amount and a decision contact.
              </div>
            )}
            {target.terminalType === "LOST" && (
              <div className="banner">{`Closing as lost requires a loss reason from the controlled list.`}</div>
            )}
            {needsCloseDate && (
              <>
                <Field label="Close date *" htmlFor="opp-close-date">
                  <input id="opp-close-date" type="date" value={closeDate}
                    onChange={(event) => setCloseDate(event.target.value)} />
                </Field>
                <Field label="Final amount *" htmlFor="opp-final">
                  <input id="opp-final" type="number" min="0" step="100" value={finalAmount}
                    onChange={(event) => setFinalAmount(event.target.value)} />
                </Field>
                <Field label="Decision contact *" htmlFor="opp-contact"
                  hint="Paste the contact id from the account's contact list.">
                  <input id="opp-contact" value={decisionContactId}
                    onChange={(event) => setDecisionContactId(event.target.value)} />
                </Field>
              </>
            )}
            {needsLossReason && (
              <Field label="Loss reason *" htmlFor="opp-loss">
                <select id="opp-loss" value={lossReasonCode}
                  onChange={(event) => setLossReasonCode(event.target.value)}>
                  <option value="">Select a reason…</option>
                  {(lossReasons.data ?? []).map((reason) => (
                    <option key={reason.code} value={reason.code}>{reason.name}</option>
                  ))}
                </select>
              </Field>
            )}
            <Field label="Reason / note" htmlFor="opp-reason"
              hint="Required when moving a deal backwards or skipping a stage.">
              <textarea id="opp-reason" value={reason} onChange={(event) => setReason(event.target.value)} />
            </Field>
          </div>
        </Modal>
      )}
    </>
  );
}
