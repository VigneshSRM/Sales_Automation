package com.infeara.sales.web;

import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.crm.activity.ActivityService;
import com.infeara.sales.crm.dto.WorkDtos;
import com.infeara.sales.crm.task.TaskService;
import com.infeara.sales.security.CurrentPrincipalProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Activities and tasks", description = "Record timelines, activities and tasks")
public class WorkController {

    private final ActivityService activityService;
    private final TaskService taskService;
    private final CurrentPrincipalProvider principalProvider;

    public WorkController(ActivityService activityService, TaskService taskService,
                          CurrentPrincipalProvider principalProvider) {
        this.activityService = activityService;
        this.taskService = taskService;
        this.principalProvider = principalProvider;
    }

    @GetMapping("/records/{relatedType}/{relatedId}/activities")
    @Operation(summary = "Timeline for a CRM record")
    public ApiResponse<PageResponse<WorkDtos.ActivityView>> timeline(
            @PathVariable String relatedType, @PathVariable String relatedId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(activityService.timeline(principalProvider.principal(), relatedType, relatedId,
                page, size), CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/activities")
    @Operation(summary = "Record a call, note, meeting summary or email log")
    public ResponseEntity<ApiResponse<WorkDtos.ActivityView>> recordActivity(
            @Valid @RequestBody WorkDtos.CreateActivityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(activityService.create(principalProvider.principal(), request),
                        CorrelationIdFilter.currentRequestId()));
    }

    @GetMapping("/tasks")
    @Operation(summary = "List tasks (mine / all / overdue)")
    public ApiResponse<PageResponse<WorkDtos.TaskView>> tasks(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "true") boolean mine, @RequestParam(defaultValue = "false") boolean overdue) {
        return ApiResponse.ok(taskService.list(principalProvider.principal(), page, size, mine, overdue),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/tasks")
    @Operation(summary = "Create a task")
    public ResponseEntity<ApiResponse<WorkDtos.TaskView>> createTask(
            @Valid @RequestBody WorkDtos.CreateTaskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(taskService.create(principalProvider.principal(), request),
                        CorrelationIdFilter.currentRequestId()));
    }

    @PostMapping("/tasks/{id}/complete")
    @Operation(summary = "Complete a task")
    public ApiResponse<WorkDtos.TaskView> completeTask(@PathVariable UUID id,
                                                       @RequestBody(required = false)
                                                       WorkDtos.CompleteTaskRequest request) {
        return ApiResponse.ok(taskService.complete(principalProvider.principal(), id,
                request == null ? new WorkDtos.CompleteTaskRequest(null) : request),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/tasks/{id}/reopen")
    @Operation(summary = "Reopen a completed task")
    public ApiResponse<WorkDtos.TaskView> reopenTask(@PathVariable UUID id) {
        return ApiResponse.ok(taskService.reopen(principalProvider.principal(), id),
                CorrelationIdFilter.currentRequestId());
    }
}
