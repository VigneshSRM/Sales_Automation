import { describe, expect, it } from "vitest";
import {
  formatDate, formatDateTime, formatMoney, formatNumber, initials, statusTone, titleCase,
} from "../format";

describe("formatMoney", () => {
  it("groups by currency and keeps two decimals", () => {
    expect(formatMoney(1234.5, "INR")).toContain("1,234.5");
  });

  it("returns an em dash for missing values instead of inventing a number", () => {
    expect(formatMoney(null, "INR")).toBe("—");
    expect(formatMoney(10, null)).toBe("—");
  });

  it("renders each record in its own currency and never converts", () => {
    expect(formatMoney(1000, "USD")).toContain("$");
    expect(formatMoney(1000, "EUR")).toContain("€");
    expect(formatMoney(1000, "USD")).not.toBe(formatMoney(1000, "EUR"));
  });
});

describe("formatDateTime", () => {
  it("renders ISO instants in the given IANA timezone", () => {
    const value = formatDateTime("2026-11-01T00:00:00Z", "Asia/Kolkata");
    expect(value).toMatch(/2026/);
    expect(value).not.toBe("");
  });

  it("shows an em dash for missing or invalid timestamps", () => {
    expect(formatDateTime(null)).toBe("—");
    expect(formatDate(undefined)).toBe("—");
    expect(formatDate("not-a-date")).toBe("—");
  });
});

describe("titleCase / initials / statusTone", () => {
  it("humanises enum codes", () => {
    expect(titleCase("PENDING_APPROVAL")).toBe("Pending Approval");
    expect(titleCase("CLOSED_WON")).toBe("Closed Won");
  });

  it("derives initials from at most two words", () => {
    expect(initials("Aarav Sharma")).toBe("AS");
    expect(initials("Madonna")).toBe("M");
  });

  it("maps statuses to accessible tones", () => {
    expect(statusTone("ACTIVE")).toBe("success");
    expect(statusTone("PENDING_APPROVAL")).toBe("warning");
    expect(statusTone("REPLIED")).toBe("success");
    expect(statusTone("BOUNCED")).toBe("danger");
    expect(statusTone("SOMETHING_ELSE")).toBe("neutral");
  });
});

describe("formatNumber", () => {
  it("formats numbers and tolerates null", () => {
    expect(formatNumber(1234)).toContain("1,234");
    expect(formatNumber(null)).toBe("—");
  });
});
