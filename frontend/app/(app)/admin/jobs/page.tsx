"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import { formatDateTime, titleCase } from "@/lib/format";
import {
  Card, EmptyState, ErrorState, PageHeader, Pagination, TableSkeleton,
} from "@/components/ui";
import { useToast } from "@/components/toast";

export default function JobsPage() {
  const [status, setStatus] = useState("DEAD");
  const [page, setPage] = useState(0);
  const queryClient = useQueryClient();
  const toast = useToast();

  const jobs = useQuery({ queryKey: ["jobs", status, page], queryFn: () => api.jobs({ status, page, size: 25 }) });
  const health = useQuery({
    queryKey: ["integration-health"],
    queryFn: api.integrationHealth,
    retry: false,
  });

  const replay = useMutation({
    mutationFn: (id: string) =>
      api.replayJob(id),
    onSuccess: () => {
      toast.push("Job requeued — it will run on the next worker tick", "success");
      queryClient.invalidateQueries({ queryKey: ["jobs"] });
    },
    onError: (error) => toast.push(error instanceof ApiError ? error.message : "Replay failed", "error"),
  });

  return (
    <>
      <PageHeader title="Jobs & automation"
        subtitle="Durable outbox and job queues. Dead work is never silently dropped." />
      <div className="grid grid--2">
        <Card title="Integration health">
          {health.isPending && <TableSkeleton rows={3} />}
          {health.isError && <p className="small" role="alert">Could not read integration health.</p>}
          <div className="stack" style={{ gap: 12 }}>
            {(health.data ?? []).map((item) => (
              <div key={item.integration} className="row row--between">
                <span>
                  <strong style={{ fontSize: 13 }}>{item.integration}</strong>
                  <div className="small muted">{item.detail}</div>
                  <div className="small muted">{item.release}</div>
                </span>
                <span className={`badge badge--${item.status === "HEALTHY" ? "success"
                  : item.status === "ERROR" ? "danger" : "warning"}`}>{titleCase(item.status)}</span>
              </div>
            ))}
          </div>
        </Card>

        <Card title="Job queue" padded={false}>
          <div className="card__body row" style={{ gap: 8 }}>
            {["DEAD", "RETRY", "PENDING", "COMPLETED", "ALL"].map((option) => (
              <button key={option} type="button" className={`btn btn--sm ${status === option ? "btn--primary" : ""}`}
                aria-pressed={status === option}
                onClick={() => { setStatus(option); setPage(0); }}>
                {titleCase(option)}
              </button>
            ))}
          </div>
          {jobs.isPending && <TableSkeleton rows={4} />}
          {jobs.isError && <ErrorState message={(jobs.error as Error).message} onRetry={() => jobs.refetch()} />}
          {jobs.data && jobs.data.items.length === 0 && (
            <EmptyState title="No jobs in this state"
              description="Queues are empty. Outbox events with no handler are marked dead so they stay visible." />
          )}
          {jobs.data && jobs.data.items.length > 0 && (
            <>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th scope="col">Job</th>
                      <th scope="col">Attempts</th>
                      <th scope="col">Last error</th>
                      <th scope="col" />
                    </tr>
                  </thead>
                  <tbody>
                    {jobs.data.items.map((job) => (
                      <tr key={job.id}>
                        <td>
                          <strong className="mono">{job.jobType}</strong>
                          <div className="small muted">{formatDateTime(job.availableAt ?? job.completedAt)}</div>
                        </td>
                        <td className="small">{job.attemptCount}/{job.maxAttempts}</td>
                        <td className="small muted" style={{ maxWidth: 320 }}>{job.lastError ?? "—"}</td>
                        <td style={{ textAlign: "right" }}>
                          {(job.status === "DEAD" || job.status === "RETRY") && (
                            <button type="button" className="btn btn--sm" disabled={replay.isPending}
                              onClick={() => replay.mutate(job.id)}>Replay</button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Pagination page={jobs.data.page} size={jobs.data.size}
                totalElements={jobs.data.totalElements} onPage={setPage} />
            </>
          )}
        </Card>
      </div>
    </>
  );
}
