package com.infeara.sales.notification;

import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.security.CurrentPrincipal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Notification centre (FR-NOTIF-001/002) with deduplication by key. */
@Service
public class NotificationService {

    private final NotificationRepository repository;
    private final NotificationPreferenceRepository preferences;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, NotificationPreferenceRepository preferences,
                               Clock clock) {
        this.repository = repository;
        this.preferences = preferences;
        this.clock = clock;
    }

    @Transactional
    public void notifyUser(String workspaceId, String userId, String type, String severity, String title, String body,
                           String link, String dedupeKey) {
        if (!isInAppEnabled(userId, type)) {
            return;
        }
        if (dedupeKey != null && repository.findByDedupeKey(userId, dedupeKey).isPresent()) {
            return;
        }
        Notification notification = new Notification();
        notification.setId(Ids.newId());
        notification.setWorkspaceId(workspaceId);
        notification.setRecipientUserId(userId);
        notification.setType(type);
        notification.setSeverity(severity);
        notification.setTitle(title);
        notification.setBody(body);
        notification.setLink(link);
        notification.setDedupeKey(dedupeKey);
        repository.save(notification);
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationView> list(CurrentPrincipal principal, int page, int size, boolean unreadOnly) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<Notification> result = repository.findByRecipientUserIdOrderByCreatedAtDesc(principal.userId(), pageable);
        List<NotificationView> items = result.getContent().stream()
                .filter(n -> !unreadOnly || n.getReadAt() == null)
                .map(this::toView)
                .toList();
        return PageResponse.of(items, pageable.getPageNumber(), pageable.getPageSize(), result.getTotalElements(),
                "createdAt:desc", List.of(unreadOnly ? "unread=true" : "unread=false"));
    }

    @Transactional(readOnly = true)
    public long unreadCount(CurrentPrincipal principal) {
        return repository.countByRecipientUserIdAndReadAtIsNull(principal.userId());
    }

    @Transactional
    public void markRead(CurrentPrincipal principal, UUID notificationId) {
        Notification notification = repository.findByIdAndRecipientUserId(notificationId.toString(),
                        principal.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Notification not found"));
        if (notification.getReadAt() == null) {
            notification.setReadAt(clock.instant());
            repository.save(notification);
        }
    }

    @Transactional
    public int markAllRead(CurrentPrincipal principal) {
        Page<Notification> unread = repository.findByRecipientUserIdOrderByCreatedAtDesc(principal.userId(),
                PageRequest.of(0, 200));
        int updated = 0;
        for (Notification notification : unread.getContent()) {
            if (notification.getReadAt() == null) {
                notification.setReadAt(clock.instant());
                repository.save(notification);
                updated++;
            }
        }
        return updated;
    }

    private boolean isInAppEnabled(String userId, String type) {
        return preferences.findByUserIdAndNotificationType(userId, type)
                .map(NotificationPreference::isInAppEnabled)
                .orElse(true);
    }

    private NotificationView toView(Notification notification) {
        return new NotificationView(UUID.fromString(notification.getId()), notification.getType(),
                notification.getSeverity(), notification.getTitle(), notification.getBody(),
                notification.getLink(), notification.getReadAt(), notification.getCreatedAt());
    }

    public record NotificationView(UUID id, String type, String severity, String title, String body, String link,
                                   java.time.Instant readAt, java.time.Instant createdAt) {
    }
}
