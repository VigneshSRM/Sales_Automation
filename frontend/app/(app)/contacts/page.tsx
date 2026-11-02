"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { api } from "@/lib/api";
import {
  Card, DataTable, EmptyState, ErrorState, PageHeader, Pagination, TableSkeleton, type Column,
} from "@/components/ui";
import type { ContactSummary } from "@/lib/types";

export default function ContactsPage() {
  const [page, setPage] = useState(0);
  const [term, setTerm] = useState("");
  const params = useMemo(() => ({ page, size: 25, q: term || undefined }), [page, term]);
  const contacts = useQuery({ queryKey: ["contacts", params], queryFn: () => api.contacts(params) });

  const columns: Column<ContactSummary>[] = [
    {
      key: "fullName", header: "Contact",
      render: (contact) => (
        <span>
          <Link className="link-strong" href={`/contacts/detail?id=${contact.id}`}>{contact.fullName}</Link>
          <div className="small muted">{contact.title ?? "Role not recorded"}</div>
        </span>
      ),
    },
    { key: "accountName", header: "Account", render: (c) => c.accountName ?? "—" },
    { key: "email", header: "Email", render: (c) => c.email ?? "—" },
    { key: "phone", header: "Phone", render: (c) => c.phone ?? "—" },
    { key: "decisionRole", header: "Decision role", render: (c) => c.decisionRole ?? "—" },
    {
      key: "consentStatus", header: "Consent",
      render: (c) => (
        <span className={`badge badge--${c.consentStatus === "GRANTED" ? "success" : c.consentStatus === "WITHDRAWN" ? "danger" : "warning"}`}>
          {c.consentStatus}
        </span>
      ),
    },
    {
      key: "suppressionStatus", header: "Suppression",
      render: (c) => c.suppressionStatus === "NONE"
        ? <span className="small muted">Clear</span>
        : <span className="badge badge--danger">{c.suppressionStatus}</span>,
    },
  ];

  return (
    <>
      <PageHeader title="Contacts" subtitle="People, roles, consent and suppression state." />
      <Card padded={false}>
        <div className="card__body">
          <div className="field" style={{ maxWidth: 360 }}>
            <label className="field__label" htmlFor="contact-search">Search contacts</label>
            <input id="contact-search" type="search" value={term} placeholder="Name or email"
              onChange={(event) => { setTerm(event.target.value); setPage(0); }} />
          </div>
        </div>
        {contacts.isPending && <TableSkeleton />}
        {contacts.isError && <ErrorState message={(contacts.error as Error).message} onRetry={() => contacts.refetch()} />}
        {contacts.data && contacts.data.items.length === 0 && (
          <EmptyState title="No contacts yet"
            description="Contacts are created when a lead is converted or when a meeting invitee is matched." />
        )}
        {contacts.data && contacts.data.items.length > 0 && (
          <>
            <DataTable columns={columns} rows={contacts.data.items} caption="Contacts" />
            <Pagination page={contacts.data.page} size={contacts.data.size}
              totalElements={contacts.data.totalElements} onPage={setPage} />
          </>
        )}
      </Card>
    </>
  );
}
