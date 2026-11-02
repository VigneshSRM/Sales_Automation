"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { api } from "@/lib/api";
import { formatDateTime, titleCase } from "@/lib/format";
import {
  Card, DataTable, EmptyState, ErrorState, PageHeader, Pagination, StatusBadge, TableSkeleton,
  type Column,
} from "@/components/ui";
import { LeadFormModal } from "@/components/lead-form";
import type { LeadSummary } from "@/lib/types";

const STATUS_OPTIONS = ["", "NEW", "RESEARCHING", "QUALIFIED", "CONTACTED", "ENGAGED",
  "MEETING_SCHEDULED", "DISCOVERY_COMPLETED", "CONVERTED", "NURTURE", "DISQUALIFIED"];

export default function LeadsPage() {
  const [creating, setCreating] = useState(false);
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState("createdAt");
  const [direction, setDirection] = useState("desc");
  const [status, setStatus] = useState("");
  const [term, setTerm] = useState("");
  const [unassigned, setUnassigned] = useState(false);

  const params = useMemo(
    () => ({ page, size: 25, sort, direction, status: status || undefined, q: term || undefined,
      unassigned: unassigned ? "true" : undefined }),
    [page, sort, direction, status, term, unassigned],
  );

  const leads = useQuery({ queryKey: ["leads", params], queryFn: () => api.leads(params) });

  const columns: Column<LeadSummary>[] = [
    {
      key: "fullName", header: "Lead", sortable: true,
      render: (lead) => (
        <span>
          <Link className="link-strong" href={`/leads/detail?id=${lead.id}`}>{lead.fullName}</Link>
          <div className="small muted">{lead.companyName}</div>
        </span>
      ),
    },
    { key: "status", header: "Status", sortable: true, render: (lead) => <StatusBadge status={lead.status} /> },
    {
      key: "score", header: "Score", sortable: true, align: "right",
      render: (lead) => (
        <span>
          <strong>{lead.score ?? "—"}</strong>
          {lead.scoreBand && <div className="small muted">{titleCase(lead.scoreBand)}</div>}
        </span>
      ),
    },
    { key: "ownerName", header: "Owner", render: (lead) => lead.ownerName ?? <span className="muted">Unassigned</span> },
    { key: "sourceName", header: "Source", render: (lead) => lead.sourceName ?? "—" },
    {
      key: "updatedAt", header: "Updated", sortable: true,
      render: (lead) => <span className="small muted">{formatDateTime(lead.updatedAt)}</span>,
    },
  ];

  const onSort = (key: string) => {
    if (sort === key) setDirection(direction === "asc" ? "desc" : "asc");
    else { setSort(key); setDirection("asc"); }
    setPage(0);
  };

  return (
    <>
      <PageHeader title="Leads" subtitle="Captured demand, ownership and qualification progress."
        actions={<button type="button" className="btn btn--primary btn--sm" onClick={() => setCreating(true)}>
          Capture lead
        </button>} />

      <Card padded={false}>
        <div className="card__body" style={{ paddingBottom: 12 }}>
          <div className="form-grid">
            <div className="field">
              <label className="field__label" htmlFor="lead-search">Search</label>
              <input id="lead-search" type="search" placeholder="Name, company or email"
                value={term} onChange={(event) => { setTerm(event.target.value); setPage(0); }} />
            </div>
            <div className="field">
              <label className="field__label" htmlFor="lead-status">Status</label>
              <select id="lead-status" value={status}
                onChange={(event) => { setStatus(event.target.value); setPage(0); }}>
                {STATUS_OPTIONS.map((option) => (
                  <option key={option} value={option}>{option ? titleCase(option) : "All statuses"}</option>
                ))}
              </select>
            </div>
            <div className="field">
              <span className="field__label">Ownership</span>
              <label className="row small" style={{ gap: 6, paddingTop: 8 }}>
                <input type="checkbox" style={{ width: 16, height: 16 }} checked={unassigned}
                  onChange={(event) => { setUnassigned(event.target.checked); setPage(0); }} />
                Unassigned only
              </label>
            </div>
          </div>
        </div>

        {leads.isPending && <TableSkeleton />}
        {leads.isError && (
          <ErrorState message={(leads.error as Error).message} onRetry={() => leads.refetch()} />
        )}
        {leads.data && leads.data.items.length === 0 && (
          <EmptyState title="No leads match these filters"
            description="Adjust the filters, or capture a lead to start the research → qualification → outreach flow." />
        )}
        {leads.data && leads.data.items.length > 0 && (
          <>
            <DataTable columns={columns} rows={leads.data.items} sort={sort} direction={direction}
              onSort={onSort} caption="Leads" />
            <Pagination page={leads.data.page} size={leads.data.size}
              totalElements={leads.data.totalElements} onPage={setPage} />
          </>
        )}
      </Card>
      {creating && (
        <LeadFormModal
          onClose={() => setCreating(false)}
          onCreated={() => { setCreating(false); leads.refetch(); }}
        />
      )}
    </>
  );
}
