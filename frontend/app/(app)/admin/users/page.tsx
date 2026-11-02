"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "@/lib/api";
import { formatDateTime, titleCase } from "@/lib/format";
import {
  Card, EmptyState, ErrorState, PageHeader, Pagination, TableSkeleton,
} from "@/components/ui";

export default function UsersPage() {
  const [page, setPage] = useState(0);
  const users = useQuery({ queryKey: ["users", page], queryFn: () => api.users({ page, size: 25 }) });
  const roles = useQuery({ queryKey: ["roles"], queryFn: api.roles });

  return (
    <>
      <PageHeader title="Users & roles" subtitle="Workspace membership, role assignment and MFA state." />
      <div className="grid grid--2">
        <Card title="Members" padded={false}>
          {users.isPending && <TableSkeleton rows={5} />}
          {users.isError && <ErrorState message={(users.error as Error).message} onRetry={() => users.refetch()} />}
          {users.data && users.data.items.length === 0 && (
            <EmptyState title="No users" description="Invite a teammate to give them workspace access." />
          )}
          {users.data && users.data.items.length > 0 && (
            <>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th scope="col">User</th>
                      <th scope="col">Roles</th>
                      <th scope="col">Scope</th>
                      <th scope="col">MFA</th>
                      <th scope="col">Status</th>
                      <th scope="col">Last login</th>
                    </tr>
                  </thead>
                  <tbody>
                    {users.data.items.map((user) => (
                      <tr key={user.id}>
                        <td>
                          <strong>{user.displayName}</strong>
                          <div className="small muted">{user.email}</div>
                        </td>
                        <td className="small">{user.roles.length ? user.roles.join(", ") : "—"}</td>
                        <td className="small">{titleCase(user.scopeType)}</td>
                        <td>
                          <span className={`badge badge--${user.mfaEnabled ? "success" : "warning"}`}>
                            {user.mfaEnabled ? "Enrolled" : "Pending"}
                          </span>
                        </td>
                        <td><span className={`badge badge--${user.status === "ACTIVE" ? "success" : "danger"}`}>
                          {titleCase(user.status)}
                        </span></td>
                        <td className="small muted">{user.lastLoginAt ? formatDateTime(user.lastLoginAt) : "Never"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Pagination page={users.data.page} size={users.data.size}
                totalElements={users.data.totalElements} onPage={setPage} />
            </>
          )}
        </Card>
        <Card title="Roles and capabilities">
          {roles.isPending && <TableSkeleton rows={4} />}
          {roles.isError && <p className="small" role="alert">Roles could not be loaded.</p>}
          <div className="stack" style={{ gap: 12 }}>
            {(roles.data ?? []).map((role) => (
              <details key={role.code}>
                <summary style={{ cursor: "pointer", fontWeight: 600, fontSize: 13 }}>
                  {role.name} <span className="small muted">({role.capabilities.length} capabilities)</span>
                </summary>
                <p className="small muted" style={{ marginTop: 6 }}>{role.description}</p>
                <div className="row" style={{ flexWrap: "wrap", gap: 6, marginTop: 6 }}>
                  {role.capabilities.map((capability) => (
                    <span key={capability} className="badge badge--neutral mono">{capability}</span>
                  ))}
                </div>
              </details>
            ))}
          </div>
        </Card>
      </div>
    </>
  );
}
