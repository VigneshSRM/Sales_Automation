"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { Card, EmptyState, ErrorState, PageHeader, Pagination, TableSkeleton } from "@/components/ui";

export default function AuditPage() {
  const [page, setPage] = useState(0);
  const [action, setAction] = useState("");
  const audit = useQuery({
    queryKey: ["audit", page, action],
    queryFn: () => api.audit({ page, size: 25, action: action || undefined }),
  });

  return (
    <>
      <PageHeader title="Audit log"
        subtitle="Append-only, hash-chained record of privileged and business-critical actions." />
      <Card padded={false}>
        <div className="card__body">
          <div className="field" style={{ maxWidth: 320 }}>
            <label className="field__label" htmlFor="audit-action">Action contains</label>
            <input id="audit-action" value={action} placeholder="e.g. LEAD, MERGE, LOGIN"
              onChange={(event) => { setAction(event.target.value.toUpperCase()); setPage(0); }} />
          </div>
        </div>
        {audit.isPending && <TableSkeleton rows={6} />}
        {audit.isError && <ErrorState message={(audit.error as Error).message} onRetry={() => audit.refetch()} />}
        {audit.data && audit.data.items.length === 0 && (
          <EmptyState title="No audit entries match" description="Audit records are written by the application, never edited." />
        )}
        {audit.data && audit.data.items.length > 0 && (
          <>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th scope="col">When</th>
                    <th scope="col">Action</th>
                    <th scope="col">Object</th>
                    <th scope="col">Actor</th>
                    <th scope="col">Outcome</th>
                    <th scope="col">Record hash</th>
                  </tr>
                </thead>
                <tbody>
                  {audit.data.items.map((entry) => (
                    <tr key={entry.id}>
                      <td className="small">{formatDateTime(entry.occurredAt)}</td>
                      <td className="mono">{entry.action}</td>
                      <td className="small">
                        {entry.objectType}
                        {entry.objectId && <div className="small muted mono">{entry.objectId}</div>}
                      </td>
                      <td className="small">
                        {entry.actorType === "USER" ? "User" : entry.actorType}
                        {entry.actorUserId && <div className="small muted mono">{entry.actorUserId}</div>}
                      </td>
                      <td>
                        <span className={`badge badge--${entry.outcome === "SUCCESS" ? "success" : "danger"}`}>
                          {entry.outcome}
                        </span>
                      </td>
                      <td className="mono" title={entry.recordHash}>{entry.recordHash.slice(0, 12)}…</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination page={audit.data.page} size={audit.data.size}
              totalElements={audit.data.totalElements} onPage={setPage} />
          </>
        )}
      </Card>
    </>
  );
}
