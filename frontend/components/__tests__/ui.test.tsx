import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { DataTable, EmptyState, Pagination, StatusBadge } from "../ui";

afterEach(cleanup);

describe("DataTable", () => {
  const columns = [
    { key: "name", header: "Name", render: (row: { id: string; name: string }) => row.name },
  ];

  it("renders rows and an accessible caption", () => {
    render(<DataTable columns={columns} rows={[{ id: "1", name: "Acme" }]} caption="Accounts" />);
    expect(screen.getByText("Acme")).toBeTruthy();
    expect(screen.getByRole("columnheader", { name: "Name" })).toBeTruthy();
  });

  it("renders nothing but stays valid when empty", () => {
    const { container } = render(<DataTable columns={columns} rows={[]} caption="Accounts" />);
    expect(container.querySelectorAll("tbody tr").length).toBe(0);
  });
});

describe("EmptyState", () => {
  it("explains the empty state to assistive tech", () => {
    render(<EmptyState title="No leads" description="Capture a lead to begin." />);
    expect(screen.getByText("No leads")).toBeTruthy();
    expect(screen.getByText("Capture a lead to begin.")).toBeTruthy();
  });
});

describe("Pagination", () => {
  it("disables previous on the first page and reports the range", () => {
    render(<Pagination page={0} size={25} totalElements={60} onPage={() => {}} />);
    const previous = screen.getByRole("button", { name: "Previous" }) as HTMLButtonElement;
    expect(previous.disabled).toBe(true);
    expect(screen.getByText(/1–25 of 60/)).toBeTruthy();
  });

  it("invokes the callback with the next page", () => {
    const onPage = vi.fn();
    render(<Pagination page={0} size={25} totalElements={60} onPage={onPage} />);
    (screen.getByRole("button", { name: "Next" }) as HTMLButtonElement).click();
    expect(onPage).toHaveBeenCalledWith(1);
  });
});

describe("StatusBadge", () => {
  it("renders a humanised status", () => {
    render(<StatusBadge status="PENDING_APPROVAL" />);
    expect(screen.getByText("Pending Approval")).toBeTruthy();
  });
});
