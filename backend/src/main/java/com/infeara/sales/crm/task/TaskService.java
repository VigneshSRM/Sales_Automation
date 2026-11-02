package com.infeara.sales.crm.task;

import com.infeara.sales.audit.AuditRecorder;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.crm.activity.ActivityRecorder;
import com.infeara.sales.crm.dto.WorkDtos;
import com.infeara.sales.identity.User;
import com.infeara.sales.identity.UserRepository;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Task management (FR-ACT-002) with overdue detection and completion notes. */
@Service
public class TaskService {

    private final TaskRepository tasks;
    private final UserRepository users;
    private final ActivityRecorder activities;
    private final CapabilityEvaluator capabilities;
    private final AuditRecorder audit;
    private final Clock clock;

    public TaskService(TaskRepository tasks, UserRepository users, ActivityRecorder activities,
                       CapabilityEvaluator capabilities, AuditRecorder audit, Clock clock) {
        this.tasks = tasks;
        this.users = users;
        this.activities = activities;
        this.capabilities = capabilities;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkDtos.TaskView> list(CurrentPrincipal principal, int page, int size, boolean mineOnly,
                                               boolean overdueOnly) {
        capabilities.require(principal, "tasks.manage");
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100),
                Sort.by(Sort.Direction.ASC, "dueAt"));
        Page<Task> result = overdueOnly
                ? tasks.findOverdue(principal.workspaceId(), clock.instant(), pageable)
                : mineOnly
                        ? tasks.findByWorkspaceIdAndOwnerUserIdAndDeletedAtIsNull(principal.workspaceId(),
                        principal.userId(), pageable)
                        : tasks.findByWorkspaceIdAndDeletedAtIsNull(principal.workspaceId(), pageable);
        List<WorkDtos.TaskView> items = result.getContent().stream().map(this::toView).toList();
        return PageResponse.of(items, pageable.getPageNumber(), pageable.getPageSize(), result.getTotalElements(),
                "dueAt:asc", List.of());
    }

    @Transactional
    public WorkDtos.TaskView create(CurrentPrincipal principal, WorkDtos.CreateTaskRequest request) {
        capabilities.require(principal, "tasks.manage");
        String ownerId = principal.userId();
        if (request.ownerUserId() != null) {
            User owner = users.findByIdAndWorkspaceIdAndDeletedAtIsNull(request.ownerUserId().toString(),
                            principal.workspaceId())
                    .orElseThrow(() -> ApiException.field("ownerUserId", "UNKNOWN_USER",
                            "Owner must be an active workspace user"));
            ownerId = owner.getId();
        }
        Task task = new Task();
        task.setId(Ids.newId());
        task.setWorkspaceId(principal.workspaceId());
        task.setOwnerUserId(ownerId);
        task.setTitle(request.title().trim());
        task.setDescription(request.description());
        task.setDueAt(request.dueAt());
        task.setPriority(request.priority() == null ? "NORMAL" : request.priority());
        task.setRelatedType(request.relatedType());
        task.setRelatedId(request.relatedId());
        task.setStatus("OPEN");
        task.setCreatedBy(principal.userId());
        tasks.save(task);
        if (request.relatedType() != null && request.relatedId() != null) {
            activities.record(principal, request.relatedType(), request.relatedId(), "TASK",
                    "Task created: " + task.getTitle(), null, Map.of("taskId", task.getId(),
                            "dueAt", task.getDueAt() == null ? "" : task.getDueAt().toString()));
        }
        audit.record(principal, "TASK_CREATED", "TASK", task.getId(), null,
                Map.of("title", task.getTitle(), "ownerUserId", ownerId), Map.of());
        return toView(task);
    }

    @Transactional
    public WorkDtos.TaskView complete(CurrentPrincipal principal, UUID taskId,
                                      WorkDtos.CompleteTaskRequest request) {
        capabilities.require(principal, "tasks.manage");
        Task task = tasks.findByIdAndWorkspaceIdAndDeletedAtIsNull(taskId.toString(), principal.workspaceId())
                .orElseThrow(() -> new ApiException(ErrorCode.TASK_NOT_FOUND, "Task not found"));
        if (!task.getOwnerUserId().equals(principal.userId()) && !principal.has("leads.assign")) {
            throw new ApiException(ErrorCode.RECORD_SCOPE_DENIED,
                    "Only the task owner or a manager can complete this task");
        }
        task.setStatus("COMPLETED");
        task.setCompletedAt(clock.instant());
        task.setCompletionNote(request.completionNote());
        tasks.save(task);
        if (task.getRelatedType() != null && task.getRelatedId() != null) {
            activities.record(principal, task.getRelatedType(), task.getRelatedId(), "TASK",
                    "Task completed: " + task.getTitle(), request.completionNote(), Map.of("taskId", task.getId()));
        }
        audit.record(principal, "TASK_COMPLETED", "TASK", task.getId(), Map.of("status", "OPEN"),
                Map.of("status", "COMPLETED"), Map.of());
        return toView(task);
    }

    @Transactional
    public WorkDtos.TaskView reopen(CurrentPrincipal principal, UUID taskId) {
        capabilities.require(principal, "tasks.manage");
        Task task = tasks.findByIdAndWorkspaceIdAndDeletedAtIsNull(taskId.toString(), principal.workspaceId())
                .orElseThrow(() -> new ApiException(ErrorCode.TASK_NOT_FOUND, "Task not found"));
        task.setStatus("OPEN");
        task.setCompletedAt(null);
        task.setCompletionNote(null);
        tasks.save(task);
        return toView(task);
    }

    private WorkDtos.TaskView toView(Task task) {
        Instant now = clock.instant();
        boolean overdue = task.getDueAt() != null && task.getDueAt().isBefore(now)
                && !"COMPLETED".equals(task.getStatus()) && !"CANCELLED".equals(task.getStatus());
        return new WorkDtos.TaskView(Ids.parse(task.getId()), task.getTitle(), task.getDescription(),
                task.getDueAt(), task.getStatus(), task.getPriority(), task.getRelatedType(), task.getRelatedId(),
                users.findById(task.getOwnerUserId()).map(User::getDisplayName).orElse(null), overdue,
                task.getCompletedAt(), task.getRowVersion());
    }
}
