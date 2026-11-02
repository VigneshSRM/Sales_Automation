"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { useSearchParamState } from "@/lib/hooks";
import { BackLink, Card, EmptyState, ErrorState, PageHeader, StatusBadge, TableSkeleton } from "@/components/ui";

export default function AccountDetailPage() {
  const [id] = useSearchParamState("id");
  const account = useQuery({
    queryKey: ["account", id],
    queryFn: () => api.account(id as string),
    enabled: Boolean(id),
  });

  if (!id) return <ErrorState title="No account selected" message="Open an account from the list." />;
  if (account.isPending) return <TableSkeleton rows={7} />;
  if (account.isError) return <ErrorState message={(account.error as Error).message} onRetry={() => account.refetch()} />;
  const data = account.data;

  return (
    <>
      <BackLink href="/accounts">All accounts</BackLink>
      <PageHeader title={data.name} subtitle={data.domain ?? "No domain recorded"} />
      <div className="grid grid--2">
        <Card title="Company profile">
          <dl className="definition-list">
            <dt>Legal name</dt><dd>{data.legalName ?? "—"}</dd>
            <dt>Website</dt><dd>{data.website ?? "—"}</dd>
            <dt>Industry</dt><dd>{data.industryCode ?? "—"}</dd>
            <dt>Size band</dt><dd>{data.sizeBand ?? "—"}</dd>
            <dt>Headquarters</dt><dd>{data.headquarters ?? "—"}</dd>
            <dt>Relationship</dt><dd><StatusBadge status={data.relationshipStatus} /></dd>
            <dt>Owner</dt><dd>{data.ownerName ?? "Unassigned"}</dd>
            <dt>Created</dt><dd>{formatDateTime(data.createdAt)}</dd>
          </dl>
          {data.description && <p className="small" style={{ marginTop: 12 }}>{data.description}</p>}
        </Card>
        <Card title={`Contacts (${data.contacts.length})`}>
          {data.contacts.length === 0 ? (
            <EmptyState title="No contacts" description="Add a decision contact before running a proposal." />
          ) : (
            <ul style={{ listStyle: "none", margin: 0, padding: 0, display: "flex", flexDirection: "column", gap: 10 }}>
              {data.contacts.map((contact) => (
                <li key={contact.id} className="row row--between">
                  <span>
                    <Link className="link-strong" href={`/contacts/detail?id=${contact.id}`}>{contact.fullName}</Link>
                    <div className="small muted">{contact.title ?? "Role not recorded"} · {contact.email ?? "no email"}</div>
                  </span>
                  <span className="row" style={{ gap: 6 }}>
                    {contact.decisionRole && <span className="badge badge--neutral">{contact.decisionRole}</span>}
                    <span className={`badge badge--${contact.consentStatus === "GRANTED" ? "success" : "warning"}`}>
                      {contact.consentStatus}
                    </span>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
    </>
  );
}
