"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { ApiError, api } from "@/lib/api";
import { formatDateTime, titleCase } from "@/lib/format";
import {
  Card, EmptyState, ErrorState, Field, Modal, PageHeader, Pagination, TableSkeleton,
} from "@/components/ui";
import { useToast } from "@/components/toast";

export default function TasksPage() {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [page, setPage] = useState(0);
  const [scope, setScope] = useState<"mine" | "all" | "overdue">("mine");
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState({ title: "", description: "", dueAt: "", priority: "NORMAL" });

  const params = useMemo(
    () => ({ page, size: 25, mine: scope === "mine", overdue: scope === "overdue" }),
    [page, scope],
  );
  const tasks = useQuery({ queryKey: ["tasks", params], queryFn: () => api.tasks(params) });

  const create = useMutation({
    mutationFn: () =>
      api.createTask({
        title: form.title.trim(),
        description: form.description.trim() || null,
        dueAt: form.dueAt ? new Date(form.dueAt).toISOString() : null,
        priority: form.priority,
      }),
    onSuccess: () => {
      toast.push("Task created", "success");
      setCreating(false);
      setForm({ title: "", description: "", dueAt: "", priority: "NORMAL" });
      queryClient.invalidateQueries({ queryKey: ["tasks"] });
      queryClient.invalidateQueries({ queryKey: ["dashboard"] });
    },
    onError: (error) => toast.push(error instanceof ApiError ? error.message : "Task could not be created", "error"),
  });

  const complete = useMutation({
    mutationFn: (taskId: string) => api.completeTask(taskId),
    onSuccess: () => {
      toast.push("Task completed", "success");
      queryClient.invalidateQueries({ queryKey: ["tasks"] });
      queryClient.invalidateQueries({ queryKey: ["dashboard"] });
    },
    onError: (error) => toast.push(error instanceof ApiError ? error.message : "Task could not be completed", "error"),
  });

  return (
    <>
      <PageHeader title="Tasks & activity" subtitle="Follow-ups, deadlines and record-level work."
        actions={<button type="button" className="btn btn--primary btn--sm" onClick={() => setCreating(true)}>
          New task
        </button>} />

      <Card padded={false}>
        <div className="card__body row" style={{ gap: 8 }}>
          {(["mine", "all", "overdue"] as const).map((option) => (
            <button key={option} type="button"
              className={`btn btn--sm ${scope === option ? "btn--primary" : ""}`}
              aria-pressed={scope === option}
              onClick={() => { setScope(option); setPage(0); }}>
              {option === "mine" ? "My tasks" : option === "all" ? "All tasks" : "Overdue"}
            </button>
          ))}
        </div>

        {tasks.isPending && <TableSkeleton rows={6} />}
        {tasks.isError && <ErrorState message={(tasks.error as Error).message} onRetry={() => tasks.refetch()} />}
        {tasks.data && tasks.data.items.length === 0 && (
          <EmptyState title="No tasks in this view"
            description="Create a task, or pick a different scope. Overdue tasks also feed the dashboard KPI." />
        )}
        {tasks.data && tasks.data.items.length > 0 && (
          <>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th scope="col">Task</th>
                    <th scope="col">Priority</th>
                    <th scope="col">Due</th>
                    <th scope="col">Owner</th>
                    <th scope="col">Status</th>
                    <th scope="col" />
                  </tr>
                </thead>
                <tbody>
                  {tasks.data.items.map((task) => (
                    <tr key={task.id}>
                      <td>
                        <strong>{task.title}</strong>
                        {task.description && <div className="small muted">{task.description}</div>}
                        {task.relatedType && (
                          <div className="small muted">Linked to {task.relatedType.toLowerCase()}</div>
                        )}
                      </td>
                      <td><span className={`badge badge--${task.priority === "URGENT" || task.priority === "HIGH" ? "danger" : "neutral"}`}>
                        {titleCase(task.priority)}
                      </span></td>
                      <td className="small">
                        {task.dueAt ? formatDateTime(task.dueAt) : "—"}
                        {task.overdue && <div><span className="badge badge--danger">Overdue</span></div>}
                      </td>
                      <td className="small">{task.ownerName ?? "—"}</td>
                      <td><span className={`badge badge--${task.status === "COMPLETED" ? "success" : "neutral"}`}>
                        {titleCase(task.status)}
                      </span></td>
                      <td style={{ textAlign: "right" }}>
                        {task.status !== "COMPLETED" && (
                          <button type="button" className="btn btn--sm" disabled={complete.isPending}
                            onClick={() => complete.mutate(task.id)}>Complete</button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination page={tasks.data.page} size={tasks.data.size}
              totalElements={tasks.data.totalElements} onPage={setPage} />
          </>
        )}
      </Card>

      {creating && (
        <Modal title="New task" onClose={() => setCreating(false)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setCreating(false)}>Cancel</button>
              <button type="submit" form="task-form" className="btn btn--primary"
                disabled={create.isPending || !form.title.trim()}>
                {create.isPending ? "Saving…" : "Create task"}
              </button>
            </>
          }>
          <form id="task-form" className="stack"
            onSubmit={(event) => { event.preventDefault(); create.mutate(); }}>
            <Field label="Title *" htmlFor="task-title">
              <input id="task-title" required value={form.title}
                onChange={(event) => setForm({ ...form, title: event.target.value })} />
            </Field>
            <Field label="Description" htmlFor="task-description">
              <textarea id="task-description" value={form.description}
                onChange={(event) => setForm({ ...form, description: event.target.value })} />
            </Field>
            <div className="form-grid">
              <Field label="Due" htmlFor="task-due">
                <input id="task-due" type="datetime-local" value={form.dueAt}
                  onChange={(event) => setForm({ ...form, dueAt: event.target.value })} />
              </Field>
              <Field label="Priority" htmlFor="task-priority">
                <select id="task-priority" value={form.priority}
                  onChange={(event) => setForm({ ...form, priority: event.target.value })}>
                  {["LOW", "NORMAL", "HIGH", "URGENT"].map((priority) => (
                    <option key={priority} value={priority}>{titleCase(priority)}</option>
                  ))}
                </select>
              </Field>
            </div>
          </form>
        </Modal>
      )}
    </>
  );
}
