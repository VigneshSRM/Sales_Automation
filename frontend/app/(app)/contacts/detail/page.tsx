"use client";

import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { useSearchParamState } from "@/lib/hooks";
import { BackLink, Card, ErrorState, PageHeader, StatusBadge, TableSkeleton } from "@/components/ui";

export default function ContactDetailPage() {
  const [id] = useSearchParamState("id");
  const contact = useQuery({
    queryKey: ["contact", id],
    queryFn: () => api.contact(id as string),
    enabled: Boolean(id),
  });

  if (!id) return <ErrorState title="No contact selected" message="Open a contact from the list." />;
  if (contact.isPending) return <TableSkeleton rows={6} />;
  if (contact.isError) return <ErrorState message={(contact.error as Error).message} onRetry={() => contact.refetch()} />;
  const data = contact.data;

  return (
    <>
      <BackLink href="/contacts">All contacts</BackLink>
      <PageHeader title={data.fullName} subtitle={data.accountName ?? "No account linked"} />
      <div className="grid grid--2">
        <Card title="Contact details">
          <dl className="definition-list">
            <dt>Title</dt><dd>{data.title ?? "—"}</dd>
            <dt>Email</dt><dd>{data.email ?? "—"}</dd>
            <dt>Phone</dt><dd>{data.phone ?? "—"}</dd>
            <dt>Decision role</dt><dd>{data.decisionRole ?? "Not recorded"}</dd>
            <dt>Last updated</dt><dd>{formatDateTime(data.updatedAt)}</dd>
          </dl>
        </Card>
        <Card title="Outreach eligibility">
          <dl className="definition-list">
            <dt>Consent</dt><dd><StatusBadge status={data.consentStatus} /></dd>
            <dt>Suppression</dt><dd><StatusBadge status={data.suppressionStatus} /></dd>
          </dl>
          <p className="small muted" style={{ marginTop: 12 }}>
            Eligibility is re-checked server-side immediately before every send. A contact on the
            suppression list can never receive outbound email, even through a sequence.
          </p>
        </Card>
      </div>
    </>
  );
}
