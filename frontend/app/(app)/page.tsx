"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { formatDateTime, formatMoney, formatNumber, titleCase } from "@/lib/format";
import { Card, EmptyState, ErrorState, KpiCard, PageHeader, TableSkeleton } from "@/components/ui";
import type { CurrencyTotal } from "@/lib/types";

/**
 * Executive dashboard.
 *
 * Nothing is computed in the browser: the server returns every figure with its metric
 * definition, and money is grouped per currency because the platform performs no FX
 * conversion (documented limitation, AMB-04).
 */
export default function DashboardPage() {
  const dashboard = useQuery({ queryKey: ["dashboard"], queryFn: () => api.dashboard() });
  const feed = useQuery({ queryKey: ["activity-feed"], queryFn: () => api.activityFeed(0, 12) });

  if (dashboard.isPending) {
    return (
      <>
        <PageHeader title="Dashboard" subtitle="Live revenue and demand health for your workspace." />
        <div className="grid grid--kpi">
          {Array.from({ length: 4 }).map((_, index) => (
            <div key={index} className="skeleton" style={{ height: 108 }} />
          ))}
        </div>
        <div style={{ marginTop: 18 }}>
          <TableSkeleton rows={5} />
        </div>
      </>
    );
  }

  if (dashboard.isError) {
    return (
      <ErrorState
        title="Dashboard unavailable"
        message={(dashboard.error as Error).message}
        onRetry={() => dashboard.refetch()}
      />
    );
  }

  const data = dashboard.data;
  const forecast = data.forecast;
  const sum = (rows: CurrencyTotal[]) => rows.reduce((total, row) => total + row.count, 0);

  return (
    <>
      <PageHeader
        title="Dashboard"
        subtitle={`Period ${data.periodStart} → ${data.periodEnd} (${data.timezone}) · generated ${formatDateTime(
          data.generatedAt,
          data.timezone,
        )}`}
        actions={
          <>
            <Link className="btn btn--sm" href="/leads">
              View leads
            </Link>
            <Link className="btn btn--sm" href="/reports">
              Reports
            </Link>
            <Link className="btn btn--primary btn--sm" href="/pipeline">
              Open pipeline
            </Link>
          </>
        }
      />

      <div className="grid grid--kpi">
        {data.openPipeline.map((row) => (
          <KpiCard
            key={`open-${row.currency}`}
            label="Open pipeline"
            value={formatMoney(row.amount, row.currency)}
            unit={`${row.count} deals`}
            definition="Sum of open opportunity amounts in this currency. Not recognised revenue."
            currency={row.currency}
          />
        ))}
        {data.weightedPipeline.map((row) => (
          <KpiCard
            key={`weighted-${row.currency}`}
            label="Weighted pipeline"
            value={formatMoney(row.amount, row.currency)}
            unit={`${row.count} deals`}
            definition="Σ(amount × stage probability ÷ 100) for open opportunities."
            currency={row.currency}
          />
        ))}
        <KpiCard
          label="Win rate"
          value={`${formatNumber(forecast.winRatePercent)}%`}
          unit={`${forecast.wonCount} won · ${forecast.lostCount} lost`}
          definition="won ÷ (won + lost) × 100 for deals that closed inside the period."
        />
        {data.wonValue.length > 0 && (
          <KpiCard
            label="Closed won"
            value={formatMoney(forecast.averageWonValue, data.wonValue[0].currency)}
            unit="average deal"
            definition="Average amount of deals marked Closed Won inside the period."
            currency={data.wonValue[0].currency}
          />
        )}
      </div>

      <div className="grid grid--2" style={{ marginTop: 18 }}>
        <Card title="Forecast by currency" actions={<Link className="small" href="/pipeline">Board →</Link>}>
          {data.openPipeline.length === 0 ? (
            <EmptyState
              title="No open opportunities"
              description="Create an opportunity from the pipeline board to populate the forecast."
              action={
                <Link className="btn btn--sm btn--primary" href="/pipeline">
                  Open pipeline
                </Link>
              }
            />
          ) : (
            <table>
              <thead>
                <tr>
                  <th scope="col">Currency</th>
                  <th scope="col" style={{ textAlign: "right" }}>
                    Open
                  </th>
                  <th scope="col" style={{ textAlign: "right" }}>
                    Weighted
                  </th>
                  <th scope="col" style={{ textAlign: "right" }}>
                    Won
                  </th>
                  <th scope="col" style={{ textAlign: "right" }}>
                    Deals
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.openPipeline.map((row) => {
                  const weighted = data.weightedPipeline.find((entry) => entry.currency === row.currency);
                  const won = data.wonValue.find((entry) => entry.currency === row.currency);
                  return (
                    <tr key={row.currency}>
                      <td>
                        <strong>{row.currency}</strong>
                      </td>
                      <td style={{ textAlign: "right" }}>{formatMoney(row.amount, row.currency)}</td>
                      <td style={{ textAlign: "right" }}>
                        {weighted ? formatMoney(weighted.amount, row.currency) : "—"}
                      </td>
                      <td style={{ textAlign: "right" }}>
                        {won ? formatMoney(won.amount, row.currency) : "—"}
                      </td>
                      <td style={{ textAlign: "right" }}>{row.count}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
          {data.limitations.length > 0 && (
            <ul className="small muted" style={{ marginTop: 12, paddingLeft: 16 }}>
              {data.limitations.map((limitation) => (
                <li key={limitation}>{limitation}</li>
              ))}
            </ul>
          )}
        </Card>

        <Card title="Activity feed" actions={<Link className="small" href="/tasks">Tasks →</Link>}>
          {feed.isPending && <TableSkeleton rows={5} />}
          {feed.isError && (
            <p className="small" role="alert">
              Activity feed could not be loaded.
            </p>
          )}
          {feed.isSuccess && feed.data.items.length === 0 && (
            <p className="small muted">No recorded activity yet.</p>
          )}
          {feed.isSuccess && feed.data.items.length > 0 && (
            <ul className="timeline">
              {feed.data.items.map((item) => (
                <li key={item.id} className="timeline__item">
                  <span className="timeline__rail" aria-hidden="true">
                    <span className="timeline__dot" />
                    <span className="timeline__line" />
                  </span>
                  <span>
                    <strong className="small">{titleCase(item.type)}</strong>
                    <div className="small">{item.subject ?? "—"}</div>
                    <div className="small muted">
                      {item.actorName ?? "System"} · {formatDateTime(item.occurredAt, data.timezone)}
                    </div>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>

      <div className="grid grid--2" style={{ marginTop: 18 }}>
        <Card title="How these numbers are calculated">
          <ul className="small muted" style={{ paddingLeft: 16 }}>
            {data.definitions.map((definition) => (
              <li key={definition}>{definition}</li>
            ))}
          </ul>
        </Card>
        <Card title="Coverage">
          <dl className="small" style={{ display: "grid", gridTemplateColumns: "1fr auto", gap: "8px 16px" }}>
            <dt>Open deals counted</dt>
            <dd style={{ margin: 0, textAlign: "right" }}>{formatNumber(sum(data.openPipeline))}</dd>
            <dt>Won inside period</dt>
            <dd style={{ margin: 0, textAlign: "right" }}>{formatNumber(forecast.wonCount)}</dd>
            <dt>Lost inside period</dt>
            <dd style={{ margin: 0, textAlign: "right" }}>{formatNumber(forecast.lostCount)}</dd>
            <dt>Currencies reported</dt>
            <dd style={{ margin: 0, textAlign: "right" }}>{data.openPipeline.length}</dd>
          </dl>
          <p className="small muted" style={{ marginTop: 12 }}>
            Metrics are computed server-side under your record scope; deals you cannot see are excluded.
          </p>
        </Card>
      </div>
    </>
  );
}
