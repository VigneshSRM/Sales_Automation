"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { formatDateTime, formatMoney } from "@/lib/format";
import { Card, EmptyState, ErrorState, PageHeader, TableSkeleton } from "@/components/ui";

export default function PipelinePage() {
  const board = useQuery({ queryKey: ["pipeline"], queryFn: api.pipeline });

  if (board.isPending) return <TableSkeleton rows={6} />;
  if (board.isError) return <ErrorState message={(board.error as Error).message} onRetry={() => board.refetch()} />;

  const columns = board.data.columns;
  if (columns.length === 0) {
    return <EmptyState title="Pipeline is not configured"
      description="Pipeline stages are seeded during workspace bootstrap. Contact your administrator." />;
  }

  return (
    <>
      <PageHeader title="Pipeline"
        subtitle={`Stage transitions are validated server-side · generated ${formatDateTime(board.data.generatedAt)}`} />
      {board.data.limitations.length > 0 && (
        <div className="banner banner--info" style={{ marginBottom: 14 }}>
          {board.data.limitations.join(" ")}
        </div>
      )}
      <div className="board" role="list" aria-label="Opportunity pipeline board">
        {columns.map((column) => (
          <section key={column.stageCode} role="listitem"
            className={`column ${column.terminalType === "WON" ? "column--terminal-won"
              : column.terminalType === "LOST" ? "column--terminal-lost" : ""}`}
            aria-label={`${column.stageName}, ${column.count} deals`}>
            <header className="column__head">
              <div className="column__title">
                {column.stageName}
                <span className="badge badge--neutral">{column.count}</span>
              </div>
              <div className="column__meta">
                {column.count > 0 ? formatMoney(column.totalAmount, column.currency) : "No deals"}
              </div>
            </header>
            <div className="column__body">
              {column.opportunities.length === 0 ? (
                <p className="small muted" style={{ padding: "8px 4px" }}>Nothing at this stage.</p>
              ) : column.opportunities.map((opportunity) => (
                <article key={opportunity.id} className="deal-card">
                  <div className="deal-card__name">
                    <Link href={`/opportunities/detail?id=${opportunity.id}`}>{opportunity.name}</Link>
                  </div>
                  <div className="small muted">{opportunity.accountName ?? "No account"}</div>
                  <div className="deal-card__meta">
                    <span>{formatMoney(opportunity.amount, opportunity.currency)}</span>
                    <span>{opportunity.probability}% · {formatMoney(opportunity.weightedValue, opportunity.currency)}</span>
                  </div>
                  <div className="deal-card__meta">
                    <span>{opportunity.ownerName ?? "Unassigned"}</span>
                    <span>{opportunity.expectedCloseDate ?? "No close date"}</span>
                  </div>
                </article>
              ))}
            </div>
          </section>
        ))}
      </div>
      <Card title="How this board is computed">
        <ul className="small muted" style={{ margin: 0, paddingLeft: 16 }}>
          <li>Open pipeline excludes terminal (won/lost) deals.</li>
          <li>Weighted value = amount × probability ÷ 100, calculated on the server.</li>
          <li>Currency totals are never mixed — a column containing several currencies reports per-currency totals.</li>
        </ul>
      </Card>
    </>
  );
}
