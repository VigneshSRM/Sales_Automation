"use client";

import Link from "next/link";
import { useEffect, useId, useRef, type ReactNode } from "react";
import { statusTone, titleCase } from "@/lib/format";

export function Card({ title, actions, children, padded = true }: {
  title?: ReactNode; actions?: ReactNode; children: ReactNode; padded?: boolean;
}) {
  return (
    <section className="card">
      {(title || actions) && (
        <header className="card__header">
          <span className="card__title">{title}</span>
          {actions && <div className="row" style={{ marginLeft: "auto" }}>{actions}</div>}
        </header>
      )}
      {padded ? <div className="card__body">{children}</div> : children}
    </section>
  );
}

export function Badge({ children, tone }: { children: ReactNode; tone?: string }) {
  const resolved = tone ?? "neutral";
  return <span className={`badge badge--${resolved}`}>{children}</span>;
}

export function StatusBadge({ status }: { status: string }) {
  const tone = statusTone(status);
  return (
    <span className={`badge badge--${tone === "info" ? "neutral" : tone}`}>
      <span className="dot" aria-hidden="true" />
      {titleCase(status)}
    </span>
  );
}

export function KpiCard({ label, value, unit, definition, currency }: {
  label: string; value: string; unit: string; definition: string; currency?: string | null;
}) {
  const display =
    unit === "currency" && currency && currency !== "MULTI"
      ? new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(Number(value))
      : value;
  return (
    <article className="card kpi">
      <div className="kpi__label">{label}</div>
      <div className="kpi__value">
        {display}
        {unit === "currency" && currency ? <span className="kpi__unit">{currency}</span> : null}
        {unit === "percent" ? <span className="kpi__unit">%</span> : null}
      </div>
      <p className="kpi__definition">{definition}</p>
    </article>
  );
}

export function EmptyState({ title, description, action }: {
  title: string; description: string; action?: ReactNode;
}) {
  return (
    <div className="empty">
      <div className="empty__title">{title}</div>
      <p className="small" style={{ maxWidth: 420 }}>{description}</p>
      {action}
    </div>
  );
}

export function ErrorState({ title = "Something went wrong", message, onRetry }: {
  title?: string; message: string; onRetry?: () => void;
}) {
  return (
    <div className="error-state" role="alert">
      <div className="error-state__title">{title}</div>
      <p className="small" style={{ maxWidth: 460 }}>{message}</p>
      {onRetry && <button type="button" className="btn btn--sm" onClick={onRetry}>Try again</button>}
    </div>
  );
}

export function TableSkeleton({ rows = 6 }: { rows?: number }) {
  return (
    <div className="card__body" aria-hidden="true">
      {Array.from({ length: rows }).map((_, index) => (
        <div key={index} className="skeleton skeleton-row" style={{ width: `${92 - index * 6}%` }} />
      ))}
    </div>
  );
}

export function PageHeader({ title, subtitle, actions }: {
  title: string; subtitle?: string; actions?: ReactNode;
}) {
  return (
    <div className="page-head">
      <div className="page-head__titles">
        <h1>{title}</h1>
        {subtitle && <p className="page-head__sub">{subtitle}</p>}
      </div>
      {actions && <div className="page-head__actions">{actions}</div>}
    </div>
  );
}

export function Field({ label, hint, error, htmlFor, children }: {
  label: string; hint?: string; error?: string; htmlFor?: string; children: ReactNode;
}) {
  return (
    <div className="field">
      <label className="field__label" htmlFor={htmlFor}>{label}</label>
      {children}
      {hint && !error && <span className="field__hint">{hint}</span>}
      {error && <span className="field__error" role="alert">{error}</span>}
    </div>
  );
}

export function Modal({ title, onClose, children, footer }: {
  title: string; onClose: () => void; children: ReactNode; footer?: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const titleId = useId();

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
      if (event.key === "Tab" && ref.current) {
        const focusable = ref.current.querySelectorAll<HTMLElement>(
          'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
        );
        if (focusable.length === 0) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first.focus();
        } else if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last.focus();
        }
      }
    };
    document.addEventListener("keydown", onKey);
    ref.current?.querySelector<HTMLElement>("input, select, textarea, button")?.focus();
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby={titleId} ref={ref}>
        <header className="modal__header">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="btn btn--ghost btn--sm" style={{ marginLeft: "auto" }}
            onClick={onClose} aria-label="Close dialog">✕</button>
        </header>
        <div className="modal__body">{children}</div>
        {footer && <footer className="modal__footer">{footer}</footer>}
      </div>
    </div>
  );
}

export type Column<T> = {
  key: string;
  header: string;
  sortable?: boolean;
  render: (row: T) => ReactNode;
  align?: "left" | "right";
};

export function DataTable<T extends { id: string }>({ columns, rows, sort, direction, onSort, caption }: {
  columns: Column<T>[];
  rows: T[];
  sort?: string;
  direction?: string;
  onSort?: (key: string) => void;
  caption: string;
}) {
  return (
    <div className="table-wrap">
      <table>
        <caption className="skip-link">{caption}</caption>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column.key} scope="col"
                style={column.align === "right" ? { textAlign: "right" } : undefined}>
                {column.sortable && onSort ? (
                  <button type="button" onClick={() => onSort(column.key)}
                    aria-label={`Sort by ${column.header}`}>
                    {column.header}
                    {sort === column.key ? (direction === "asc" ? " ▲" : " ▼") : ""}
                  </button>
                ) : (
                  column.header
                )}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id}>
              {columns.map((column) => (
                <td key={column.key}
                  style={column.align === "right" ? { textAlign: "right" } : undefined}>
                  {column.render(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function Pagination({ page, size, totalElements, onPage }: {
  page: number; size: number; totalElements: number; onPage: (page: number) => void;
}) {
  const totalPages = Math.max(1, Math.ceil(totalElements / Math.max(size, 1)));
  return (
    <div className="row row--between" style={{ padding: "10px 12px" }}>
      <span className="small muted">
        {totalElements === 0
          ? "No records"
          : `Showing ${page * size + 1}–${Math.min((page + 1) * size, totalElements)} of ${totalElements}`}
      </span>
      <div className="row">
        <button type="button" className="btn btn--sm" disabled={page <= 0}
          onClick={() => onPage(page - 1)}>Previous</button>
        <span className="small muted">Page {page + 1} of {totalPages}</span>
        <button type="button" className="btn btn--sm" disabled={page + 1 >= totalPages}
          onClick={() => onPage(page + 1)}>Next</button>
      </div>
    </div>
  );
}

export function Tabs({ tabs, active, onChange }: {
  tabs: { key: string; label: string; count?: number }[];
  active: string;
  onChange: (key: string) => void;
}) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map((tab) => (
        <button key={tab.key} role="tab" type="button" className={`tab ${active === tab.key ? "tab--active" : ""}`}
          aria-selected={active === tab.key} onClick={() => onChange(tab.key)}>
          {tab.label}{typeof tab.count === "number" ? ` (${tab.count})` : ""}
        </button>
      ))}
    </div>
  );
}

export function BackLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <Link href={href} className="small muted" style={{ display: "inline-block", marginBottom: 8 }}>
      ← {children}
    </Link>
  );
}
