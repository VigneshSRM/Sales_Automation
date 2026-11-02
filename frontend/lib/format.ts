/** Presentation helpers. All timestamps are rendered in the user's IANA timezone. */

export function formatDateTime(iso: string | null | undefined, timezone?: string): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: timezone,
  }).format(date);
}

export function formatDate(iso: string | null | undefined, timezone?: string): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeZone: timezone }).format(date);
}

/**
 * Money is always rendered with the record's own currency. When the currency is missing we
 * show an em dash rather than defaulting to INR — silently mislabelling money is worse than
 * showing nothing (AMB-04: no conversion, no assumption).
 */
export function formatMoney(amount: number | null | undefined, currency: string | null | undefined): string {
  if (amount === null || amount === undefined) return "—";
  if (!currency || currency.length !== 3) return "—";
  try {
    return new Intl.NumberFormat("en-IN", {
      style: "currency",
      currency,
      minimumFractionDigits: 0,
      maximumFractionDigits: 2,
    }).format(amount);
  } catch {
    return `${currency} ${amount.toLocaleString()}`;
  }
}

export function relativeTime(iso: string | null | undefined): string {
  if (!iso) return "";
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "";
  const diffMinutes = Math.round((Date.now() - then) / 60000);
  const formatter = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });
  if (Math.abs(diffMinutes) < 60) return formatter.format(-diffMinutes, "minute");
  const hours = Math.round(diffMinutes / 60);
  if (Math.abs(hours) < 24) return formatter.format(-hours, "hour");
  const days = Math.round(hours / 24);
  if (Math.abs(days) < 30) return formatter.format(-days, "day");
  return formatter.format(-Math.round(days / 30), "month");
}

export function titleCase(value: string): string {
  return value
    .toLowerCase()
    .split(/[_\s]+/)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

export function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? "")
    .join("");
}

export function statusTone(
  status: string,
): "neutral" | "success" | "warning" | "danger" | "info" {
  switch (status) {
    case "QUALIFIED":
    case "CONVERTED":
    case "COMPLETED":
    case "ACTIVE":
    case "APPROVED":
    case "SENT":
    case "DELIVERED":
    case "REPLIED":
    case "WON":
    case "HEALTHY":
      return "success";
    case "NURTURE":
    case "RESEARCHING":
    case "PENDING":
    case "PENDING_APPROVAL":
    case "SCHEDULED":
    case "QUEUED":
    case "PAUSED":
    case "DRAFT":
    case "OPEN":
    case "PROPOSAL_SENT":
      return "warning";
    case "DISQUALIFIED":
    case "ARCHIVED":
    case "CANCELLED":
    case "DEAD":
    case "FAILED":
    case "ERROR":
    case "BOUNCED":
    case "DISABLED":
    case "CLOSED_LOST":
    case "LOST":
    case "INACTIVE":
      return "danger";
    case "NEW":
    case "CONTACTED":
    case "ENGAGED":
    case "MEETING_SCHEDULED":
    case "DISCOVERY_COMPLETED":
    case "SENDING":
      return "info";
    default:
      return "neutral";
  }
}

export function formatNumber(value: number | null | undefined, options?: Intl.NumberFormatOptions): string {
  if (value === null || value === undefined || Number.isNaN(value)) return "—";
  return new Intl.NumberFormat("en-IN", options).format(value);
}
