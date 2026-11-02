"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { api } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import {
  Card, DataTable, EmptyState, ErrorState, PageHeader, Pagination, StatusBadge, TableSkeleton,
  type Column,
} from "@/components/ui";
import type { AccountSummary } from "@/lib/types";

export default function AccountsPage() {
  const [page, setPage] = useState(0);
  const [term, setTerm] = useState("");
  const [sort, setSort] = useState("name");
  const [direction, setDirection] = useState("asc");

  const params = useMemo(() => ({ page, size: 25, sort, direction, q: term || undefined }),
    [page, sort, direction, term]);
  const accounts = useQuery({ queryKey: ["accounts", params], queryFn: () => api.accounts(params) });

  const columns: Column<AccountSummary>[] = [
    {
      key: "name", header: "Account", sortable: true,
      render: (account) => (
        <span>
          <Link className="link-strong" href={`/accounts/detail?id=${account.id}`}>{account.name}</Link>
          <div className="small muted">{account.domain ?? "No domain recorded"}</div>
        </span>
      ),
    },
    { key: "relationshipStatus", header: "Relationship", render: (a) => <StatusBadge status={a.relationshipStatus} /> },
    { key: "industryCode", header: "Industry", render: (a) => a.industryCode ?? "—" },
    { key: "sizeBand", header: "Size", render: (a) => a.sizeBand ?? "—" },
    { key: "contactCount", header: "Contacts", align: "right", render: (a) => a.contactCount },
    { key: "openOpportunityCount", header: "Open deals", align: "right", render: (a) => a.openOpportunityCount },
    { key: "ownerName", header: "Owner", render: (a) => a.ownerName ?? <span className="muted">Unassigned</span> },
    { key: "updatedAt", header: "Updated", sortable: true,
      render: (a) => <span className="small muted">{formatDateTime(a.updatedAt)}</span> },
  ];

  return (
    <>
      <PageHeader title="Accounts" subtitle="Companies engaged with Infeara, their contacts and open deals." />
      <Card padded={false}>
        <div className="card__body">
          <div className="field" style={{ maxWidth: 360 }}>
            <label className="field__label" htmlFor="account-search">Search accounts</label>
            <input id="account-search" type="search" value={term} placeholder="Company name"
              onChange={(event) => { setTerm(event.target.value); setPage(0); }} />
          </div>
        </div>
        {accounts.isPending && <TableSkeleton />}
        {accounts.isError && <ErrorState message={(accounts.error as Error).message} onRetry={() => accounts.refetch()} />}
        {accounts.data && accounts.data.items.length === 0 && (
          <EmptyState title="No accounts yet"
            description="Accounts are created when a qualified lead is converted, or manually from an opportunity." />
        )}
        {accounts.data && accounts.data.items.length > 0 && (
          <>
            <DataTable columns={columns} rows={accounts.data.items} sort={sort} direction={direction}
              onSort={(key) => {
                if (sort === key) setDirection(direction === "asc" ? "desc" : "asc");
                else { setSort(key); setDirection("asc"); }
              }} caption="Accounts" />
            <Pagination page={accounts.data.page} size={accounts.data.size}
              totalElements={accounts.data.totalElements} onPage={setPage} />
          </>
        )}
      </Card>
    </>
  );
}
