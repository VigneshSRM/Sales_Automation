"use client";

import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import Link from "next/link";
import { api } from "@/lib/api";
import { formatMoney, formatNumber, titleCase } from "@/lib/format";
import { Card, EmptyState, ErrorState, Field, PageHeader, TableSkeleton } from "@/components/ui";
import type { CurrencyTotal } from "@/lib/types";

/**
 * Revenue reports.
 *
 * Every figure comes from the server forecast endpoint, which already applies record scope
 * and never converts currencies. The CSV export is a client-side serialisation of exactly
 * the rows on screen — it cannot invent figures.
 */
function toCsv(rows: (string | number)[][]): string {
  return rows
    .map((row) =>
      row
        .map((cell) => {
          const value = String(cell ?? "");
          return /[",\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
        })
        .join(","),
    )
    .join("\r\n");
}

function download(filename: string, content: string) {
  const blob = new Blob([content], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

function firstOfMonth(): string {
  const now = new Date();
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1)).toISOString().slice(0, 10);
}

function lastOfMonth(): string {
  const now = new Date();
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 0)).toISOString().slice(0, 10);
}

export default function ReportsPage() {
  const [from, setFrom] = useState(firstOfMonth());
  const [to, setTo] = useState(lastOfMonth());

  const dashboard = useQuery({
    queryKey: ["dashboard", from, to],
    queryFn: () => api.dashboard(from, to),
  });

  const currencies: CurrencyTotal[] = useMemo(() => dashboard.data?.openPipeline ?? [], [dashboard.data]);

  const exportCsv = () => {
    if (!dashboard.data) return;
    const rows: (string | number)[][] = [
      ["Currency", "Open amount", "Weighted amount", "Won amount", "Open deals", "Won deals"],
    ];
    currencies.forEach((row) => {
      const weighted = dashboard.data.weightedPipeline.find((entry) => entry.currency === row.currency);
      const won = dashboard.data.wonValue.find((entry) => entry.currency === row.currency);
      rows.push([
        row.currency,
        row.amount.toFixed(2),
        (weighted?.amount ?? 0).toFixed(2),
        (won?.amount ?? 0).toFixed(2),
        row.count,
        won?.count ?? 0,
      ]);
    });
    rows.push([]);
    rows.push(["Period", `${dashboard.data.periodStart} → ${dashboard.data.periodEnd}`]);
    rows.push(["Win rate %", dashboard.data.forecast.winRatePercent]);
    rows.push(["Average won value", dashboard.data.forecast.averageWonValue]);
    rows.push(["Generated at (UTC)", dashboard.data.generatedAt]);
    download(`infeara-pipeline-${dashboard.data.periodStart}_${dashboard.data.periodEnd}.csv`, toCsv(rows));
  };

  return (
    <>
      <PageHeader
        title="Revenue reports"
        subtitle="Pipeline, forecast and win-rate reporting. Amounts stay in their own currency — no FX conversion is performed."
        actions={
          <button
            type="button"
            className="btn btn--sm"
            disabled={!dashboard.data}
            onClick={exportCsv}
          >
            Export CSV
          </button>
        }
      />

      <Card>
        <div className="form-grid" style={{ maxWidth: 520 }}>
          <Field label="From" htmlFor="report-from">
            <input
              id="report-from"
              type="date"
              value={from}
              max={to}
              onChange={(event) => setFrom(event.target.value)}
            />
          </Field>
          <Field label="To" htmlFor="report-to">
            <input
              id="report-to"
              type="date"
              value={to}
              min={from}
              onChange={(event) => setTo(event.target.value)}
            />
          </Field>
        </div>
      </Card>

      {dashboard.isPending && <div style={{ marginTop: 18 }}><TableSkeleton rows={6} /></div>}
      {dashboard.isError && (
        <div style={{ marginTop: 18 }}>
          <ErrorState
            title="Report unavailable"
            message={(dashboard.error as Error).message}
            onRetry={() => dashboard.refetch()}
          />
        </div>
      )}

      {dashboard.isSuccess && (
        <>
          <div className="grid grid--kpi" style={{ marginTop: 18 }}>
            <div className="kpi card">
              <div className="kpi__label">Win rate</div>
              <div className="kpi__value">{formatNumber(dashboard.data.forecast.winRatePercent)}%</div>
              <div className="kpi__definition">
                {dashboard.data.forecast.wonCount} won · {dashboard.data.forecast.lostCount} lost
              </div>
            </div>
            <div className="kpi card">
              <div className="kpi__label">Average won value</div>
              <div className="kpi__value">
                {formatMoney(dashboard.data.forecast.averageWonValue, dashboard.data.forecast.wonCurrency)}
              </div>
              <div className="kpi__definition">Mean amount of deals closed won in the period.</div>
            </div>
            <div className="kpi card">
              <div className="kpi__label">Currencies</div>
              <div className="kpi__value">{currencies.length}</div>
              <div className="kpi__definition">Each currency is reported separately (AMB-04).</div>
            </div>
          </div>

          <Card
            title={`Pipeline by currency · ${dashboard.data.periodStart} → ${dashboard.data.periodEnd}`}
            padded={false}
            actions={<Link className="small" href="/pipeline">Open board →</Link>}
          >
            {currencies.length === 0 ? (
              <EmptyState
                title="No open pipeline in this period"
                description="Adjust the date range or create opportunities in the pipeline board."
              />
            ) : (
              <div className="table-wrap">
                <table>
                  <caption className="skip-link">Pipeline amounts grouped by currency</caption>
                  <thead>
                    <tr>
                      <th scope="col">Currency</th>
                      <th scope="col" style={{ textAlign: "right" }}>Open</th>
                      <th scope="col" style={{ textAlign: "right" }}>Weighted</th>
                      <th scope="col" style={{ textAlign: "right" }}>Won</th>
                      <th scope="col" style={{ textAlign: "right" }}>Open deals</th>
                      <th scope="col" style={{ textAlign: "right" }}>Won deals</th>
                    </tr>
                  </thead>
                  <tbody>
                    {currencies.map((row) => {
                      const weighted = dashboard.data.weightedPipeline.find(
                        (entry) => entry.currency === row.currency,
                      );
                      const won = dashboard.data.wonValue.find((entry) => entry.currency === row.currency);
                      return (
                        <tr key={row.currency}>
                          <td><strong>{row.currency}</strong></td>
                          <td style={{ textAlign: "right" }}>{formatMoney(row.amount, row.currency)}</td>
                          <td style={{ textAlign: "right" }}>
                            {weighted ? formatMoney(weighted.amount, row.currency) : "—"}
                          </td>
                          <td style={{ textAlign: "right" }}>
                            {won ? formatMoney(won.amount, row.currency) : "—"}
                          </td>
                          <td style={{ textAlign: "right" }}>{row.count}</td>
                          <td style={{ textAlign: "right" }}>{won?.count ?? 0}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          <div className="grid grid--2" style={{ marginTop: 18 }}>
            <Card title="Metric definitions">
              <ul className="small muted" style={{ paddingLeft: 16 }}>
                {dashboard.data.definitions.map((definition) => (
                  <li key={definition}>{definition}</li>
                ))}
              </ul>
            </Card>
            <Card title="Known limitations">
              <ul className="small muted" style={{ paddingLeft: 16 }}>
                {dashboard.data.limitations.map((limitation) => (
                  <li key={limitation}>{limitation}</li>
                ))}
              </ul>
              <p className="small muted" style={{ marginTop: 10 }}>
                Timezone: {titleCase(dashboard.data.timezone)} · figures are computed server-side
                under your record scope.
              </p>
            </Card>
          </div>
        </>
      )}
    </>
  );
}
