package com.infeara.sales.web;

import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.notification.NotificationService;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipalProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Notification centre")
public class NotificationController {

    private final NotificationService notifications;
    private final CurrentPrincipalProvider principalProvider;
    private final CapabilityEvaluator capabilities;

    public NotificationController(NotificationService notifications, CurrentPrincipalProvider principalProvider,
                                  CapabilityEvaluator capabilities) {
        this.notifications = notifications;
        this.principalProvider = principalProvider;
        this.capabilities = capabilities;
    }

    @GetMapping
    @Operation(summary = "List my notifications")
    public ApiResponse<PageResponse<NotificationService.NotificationView>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        var principal = principalProvider.principal();
        capabilities.require(principal, "notifications.read");
        var result = notifications.list(principal, page, size, unreadOnly);
        return ApiResponse.ok(result, CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Unread notification count")
    public ApiResponse<Map<String, Long>> unreadCount() {
        var principal = principalProvider.principal();
        return ApiResponse.ok(Map.of("count", notifications.unreadCount(principal)),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark one notification as read")
    public ApiResponse<Map<String, String>> markRead(@PathVariable UUID id) {
        notifications.markRead(principalProvider.principal(), id);
        return ApiResponse.ok(Map.of("status", "READ"), CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark all notifications as read")
    public ApiResponse<Map<String, Integer>> markAllRead() {
        int updated = notifications.markAllRead(principalProvider.principal());
        return ApiResponse.ok(Map.of("updated", updated), CorrelationIdFilter.currentRequestId());
    }
}
