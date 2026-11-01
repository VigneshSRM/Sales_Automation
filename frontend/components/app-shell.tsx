"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, buildQuery } from "@/lib/api";
import { initials, formatDateTime, relativeTime } from "@/lib/format";
import { useCapability, useLogout, useRequireSession } from "@/lib/hooks";
import { useToast } from "./toast";
import { Modal, Field, ErrorState } from "./ui";
import type { SearchHit } from "@/lib/types";

type NavItem = { href: string; label: string; capability?: string; badge?: number };
type NavSection = { title: string; items: NavItem[] };

/** Sidebar navigation is capability-driven: a link only appears if the server says the
 *  user may use the underlying module (the server still enforces it on every request). */
function navigation(capabilities: string[]): NavSection[] {
  const can = (capability?: string) => !capability || capabilities.includes(capability);
  const sections: NavSection[] = [
    {
      title: "Overview",
      items: [
        { href: "/", label: "Dashboard", capability: "reports.read" },
        { href: "/tasks", label: "Tasks & activity", capability: "tasks.manage" },
      ],
    },
    {
      title: "Demand",
      items: [
        { href: "/leads", label: "Leads", capability: "leads.read" },
        { href: "/accounts", label: "Accounts", capability: "accounts.read" },
        { href: "/contacts", label: "Contacts", capability: "contacts.read" },
      ],
    },
    {
      title: "Revenue",
      items: [
        { href: "/pipeline", label: "Pipeline", capability: "opportunities.read" },
        { href: "/reports", label: "Reports", capability: "reports.read" },
      ],
    },
    {
      title: "Engagement",
      items: [
        { href: "/engagement/mailbox", label: "Unified inbox", capability: "email.conversations.read" },
        { href: "/engagement/inbox", label: "Outbound & approvals", capability: "email.read" },
        { href: "/engagement/templates", label: "Templates", capability: "email.read" },
        { href: "/engagement/sequences", label: "Sequences", capability: "email.read" },
        { href: "/engagement/smtp", label: "Sending accounts", capability: "smtp.manage" },
        { href: "/engagement/mailbox/settings", label: "Mailbox connections", capability: "email.accounts.read" },
        { href: "/engagement/mailbox/rules", label: "Response policy", capability: "email.rules.manage" },
      ],
    },
    {
      title: "Administration",
      items: [
        { href: "/admin/users", label: "Users & roles", capability: "users.manage" },
        { href: "/admin/catalog", label: "Catalogues", capability: "catalog.manage" },
        { href: "/admin/audit", label: "Audit log", capability: "audit.read" },
        { href: "/admin/jobs", label: "Jobs & automation", capability: "jobs.manage" },
      ],
    },
  ];
  return sections
    .map((section) => ({ ...section, items: section.items.filter((item) => can(item.capability)) }))
    .filter((section) => section.items.length > 0);
}

export function AppShell({ children }: { children: ReactNode }) {
  const session = useRequireSession();
  const pathname = usePathname();
  const [navOpen, setNavOpen] = useState(false);
  const [quickCreate, setQuickCreate] = useState(false);
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  const logout = useLogout();
  const toast = useToast();

  useEffect(() => setNavOpen(false), [pathname]);

  if (session.isPending) {
    return (
      <div className="content" aria-busy="true">
        <div className="skeleton" style={{ height: 32, width: 240, marginBottom: 20 }} />
        <div className="grid grid--kpi">
          {Array.from({ length: 4 }).map((_, index) => (
            <div key={index} className="skeleton" style={{ height: 110 }} />
          ))}
        </div>
      </div>
    );
  }
  if (session.isError) {
    return (
      <div className="content">
        <ErrorState message="The workspace could not be loaded. The API may be unavailable."
          onRetry={() => session.refetch()} />
      </div>
    );
  }
  if (!session.data) return null;

  const sections = navigation(session.data.capabilities);

  return (
    <div className="shell">
      <a className="skip-link" href="#main-content">Skip to main content</a>
      <aside className={`sidebar ${navOpen ? "sidebar--open" : ""}`} aria-label="Main navigation">
        <div className="sidebar__brand">
          <span className="sidebar__brand-mark" aria-hidden="true">IS</span>
          <span>Infeara Sales OS</span>
        </div>
        <nav>
          {sections.map((section) => (
            <div key={section.title}>
              <div className="sidebar__section">{section.title}</div>
              <div className="sidebar__nav">
                {section.items.map((item) => {
                  const active = item.href === "/" ? pathname === "/" : pathname.startsWith(item.href);
                  return (
                    <Link key={item.href} href={item.href}
                      className={`sidebar__link ${active ? "sidebar__link--active" : ""}`}
                      aria-current={active ? "page" : undefined}>
                      {item.label}
                    </Link>
                  );
                })}
              </div>
            </div>
          ))}
        </nav>
        <div className="sidebar__footer">
          <div style={{ color: "#cfd8e3", fontWeight: 600 }}>{session.data.displayName}</div>
          <div>{session.data.email}</div>
          <div style={{ marginTop: 4 }}>
            Scope: {session.data.scopeType.toLowerCase()} · {session.data.timezone}
          </div>
          <button type="button" className="btn btn--ghost btn--sm" style={{ color: "#cfd8e3", marginTop: 8 }}
            onClick={() => logout.mutate()}>
            Sign out
          </button>
        </div>
      </aside>

      <div className="main">
        <header className="header">
          <button type="button" className="btn btn--ghost btn--sm"
            style={{ display: "none" }} aria-hidden="true">Menu</button>
          <GlobalSearch onNavigate={() => setNavOpen(false)} />
          <div className="header__actions">
            <button type="button" className="btn btn--primary btn--sm" onClick={() => setQuickCreate(true)}>
              + Create
            </button>
            <button type="button" className="btn btn--sm" onClick={() => setNotificationsOpen(true)}
              aria-haspopup="dialog">
              Notifications
            </button>
            <span className="badge" aria-label={`Signed in as ${session.data.displayName}`}>
              {initials(session.data.displayName)}
            </span>
          </div>
        </header>
        <main id="main-content" className="content">{children}</main>
      </div>

      {quickCreate && (
        <QuickCreateModal
          servicesCapability={session.data.capabilities}
          onClose={() => setQuickCreate(false)}
          onCreated={(message) => {
            toast.push(message, "success");
            setQuickCreate(false);
          }}
          onError={(message) => toast.push(message, "error")}
        />
      )}
      {notificationsOpen && (
        <NotificationCentre onClose={() => setNotificationsOpen(false)} />
      )}
    </div>
  );
}

function GlobalSearch({ onNavigate }: { onNavigate: () => void }) {
  const router = useRouter();
  const [term, setTerm] = useState("");
  const [open, setOpen] = useState(false);
  const [debounced, setDebounced] = useState("");
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(term.trim()), 250);
    return () => window.clearTimeout(timer);
  }, [term]);

  const results = useQuery({
    queryKey: ["search", debounced],
    queryFn: () => api.search(debounced),
    enabled: debounced.length >= 2,
    staleTime: 15_000,
  });

  useEffect(() => {
    const onClick = (event: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onClick);
    return () => document.removeEventListener("mousedown", onClick);
  }, []);

  const go = (hit: SearchHit) => {
    setOpen(false);
    setTerm("");
    onNavigate();
    router.push(`${hit.link}${hit.link.includes("?") ? "&" : "?"}id=${hit.id}`);
  };

  return (
    <div className="header__search" ref={containerRef}>
      <span className="header__search-icon" aria-hidden="true">⌕</span>
      <input type="search" placeholder="Search leads, accounts, contacts, opportunities"
        aria-label="Global search" value={term} role="combobox" aria-expanded={open}
        aria-controls="global-search-results"
        onChange={(event) => { setTerm(event.target.value); setOpen(true); }}
        onFocus={() => setOpen(true)}
        onKeyDown={(event) => {
          if (event.key === "Enter" && results.data && results.data.length > 0) go(results.data[0]);
          if (event.key === "Escape") setOpen(false);
        }} />
      {open && debounced.length >= 2 && (
        <div className="card" id="global-search-results" role="listbox"
          style={{ position: "absolute", top: 42, left: 0, right: 0, zIndex: 40, boxShadow: "var(--shadow-lg)" }}>
          {results.isPending && <div className="card__body small muted">Searching…</div>}
          {results.isError && <div className="card__body small" role="alert">Search is unavailable.</div>}
          {results.data && results.data.length === 0 && (
            <div className="card__body small muted">No matches for “{debounced}”.</div>
          )}
          {results.data?.map((hit) => (
            <button key={`${hit.type}-${hit.id}`} type="button" role="option" aria-selected={false}
              className="sidebar__link" style={{ color: "var(--text)", borderRadius: 0 }}
              onClick={() => go(hit)}>
              <span className="badge badge--neutral">{hit.type.toLowerCase()}</span>
              <span style={{ minWidth: 0 }}>
                <div style={{ fontWeight: 600 }}>{hit.title}</div>
                <div className="small muted">{hit.subtitle}</div>
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function NotificationCentre({ onClose }: { onClose: () => void }) {
  const query = useQuery({
    queryKey: ["notifications"],
    queryFn: () => api.notifications({ page: 0, size: 20 }),
  });

  return (
    <Modal title="Notification centre" onClose={onClose}
      footer={<button type="button" className="btn" onClick={onClose}>Close</button>}>
      {query.isPending && <p className="small muted">Loading notifications…</p>}
      {query.isError && <p className="small" role="alert">Notifications could not be loaded.</p>}
      {query.data?.items.length === 0 && <p className="small muted">You have no notifications.</p>}
      <ul style={{ listStyle: "none", padding: 0, margin: 0, display: "flex", flexDirection: "column", gap: 10 }}>
        {query.data?.items.map((notification) => (
          <li key={notification.id} className="card" style={{ padding: 12 }}>
            <div className="row row--between">
              <strong style={{ fontSize: 13 }}>{notification.title}</strong>
              <span className="small muted">{relativeTime(notification.createdAt)}</span>
            </div>
            {notification.body && <p className="small muted" style={{ marginTop: 4 }}>{notification.body}</p>}
            <div className="row" style={{ marginTop: 8, gap: 8 }}>
              {notification.severity !== "INFO" && (
                <span className={`badge badge--${notification.severity === "CRITICAL" ? "danger" : "warning"}`}>
                  {notification.severity}
                </span>
              )}
              {notification.link && <Link className="small" href={notification.link}>Open</Link>}
              <span className="small muted" style={{ marginLeft: "auto" }}>
                {formatDateTime(notification.createdAt)}
              </span>
            </div>
          </li>
        ))}
      </ul>
    </Modal>
  );
}

/**
 * Quick-create. Only offers record types the API can genuinely create today; the
 * create call posts to the real endpoint and surfaces validation errors verbatim.
 */
function QuickCreateModal({ onClose, onCreated, onError, servicesCapability }: {
  onClose: () => void;
  onCreated: (message: string) => void;
  onError: (message: string) => void;
  servicesCapability: string[];
}) {
  const [kind, setKind] = useState<"LEAD" | "ACCOUNT">("LEAD");
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [company, setCompany] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);

  const canAccounts = servicesCapability.includes("accounts.create");

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      if (kind === "LEAD") {
        await api.createLead(
          {
            firstName: firstName.trim(),
            lastName: lastName.trim() || null,
            companyName: company.trim(),
            email: email.trim() || null,
            phone: phone.trim() || null,
            sourceCode: "MANUAL",
          },
          crypto.randomUUID(),
        );
        onCreated("Lead created");
      } else {
        await api.createAccount({ name: name.trim() });
        onCreated("Account created");
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : "Could not save the record";
      onError(message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title="Quick create" onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose}>Cancel</button>
          <button type="submit" form="quick-create" className="btn btn--primary" disabled={busy}>
            {busy ? "Saving…" : "Create"}
          </button>
        </>
      }>
      <form id="quick-create" onSubmit={submit} className="stack">
        <Field label="Record type" htmlFor="qc-kind">
          <select id="qc-kind" value={kind} onChange={(event) => setKind(event.target.value as "LEAD" | "ACCOUNT")}>
            <option value="LEAD">Lead</option>
            {canAccounts && <option value="ACCOUNT">Account</option>}
          </select>
        </Field>
        {kind === "LEAD" ? (
          <div className="form-grid">
            <Field label="First name" htmlFor="qc-first">
              <input id="qc-first" required value={firstName} onChange={(e) => setFirstName(e.target.value)} />
            </Field>
            <Field label="Last name" htmlFor="qc-last">
              <input id="qc-last" value={lastName} onChange={(e) => setLastName(e.target.value)} />
            </Field>
            <Field label="Company" htmlFor="qc-company">
              <input id="qc-company" required value={company} onChange={(e) => setCompany(e.target.value)} />
            </Field>
            <Field label="Email" htmlFor="qc-email" hint="Duplicates are flagged automatically.">
              <input id="qc-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
            </Field>
            <Field label="Phone" htmlFor="qc-phone" hint="Stored in E.164 format when valid.">
              <input id="qc-phone" value={phone} onChange={(e) => setPhone(e.target.value)} />
            </Field>
          </div>
        ) : (
          <Field label="Account name" htmlFor="qc-name">
            <input id="qc-name" required value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
        )}
      </form>
    </Modal>
  );
}
