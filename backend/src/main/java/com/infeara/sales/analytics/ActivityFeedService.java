package com.infeara.sales.analytics;

import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.crm.activity.Activity;
import com.infeara.sales.crm.activity.ActivityRepository;
import com.infeara.sales.identity.User;
import com.infeara.sales.identity.UserRepository;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workspace activity feed used by the dashboard and the notification drawer. */
@Service
public class ActivityFeedService {

    private final ActivityRepository activities;
    private final UserRepository users;
    private final CapabilityEvaluator capabilities;

    public ActivityFeedService(ActivityRepository activities, UserRepository users,
                               CapabilityEvaluator capabilities) {
        this.activities = activities;
        this.users = users;
        this.capabilities = capabilities;
    }

    public record FeedItem(UUID id, String type, String relatedType, String relatedId, String subject,
                           String actorName, Instant occurredAt) {
    }

    @Transactional(readOnly = true)
    public PageResponse<FeedItem> feed(CurrentPrincipal principal, int page, int size) {
        capabilities.require(principal, "activities.read");
        Page<Activity> result = activities.findByWorkspaceIdOrderByOccurredAtDesc(principal.workspaceId(),
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)));
        List<FeedItem> items = result.getContent().stream().map(this::toItem).toList();
        return PageResponse.of(items, page, Math.min(Math.max(1, size), 100), result.getTotalElements(),
                "occurredAt:desc", List.of());
    }

    private FeedItem toItem(Activity activity) {
        String actor = activity.getActorUserId() == null ? "System"
                : users.findById(activity.getActorUserId()).map(User::getDisplayName).orElse("System");
        return new FeedItem(Ids.parse(activity.getId()), activity.getType(), activity.getRelatedType(),
                activity.getRelatedId(), activity.getSubject(), actor, activity.getOccurredAt());
    }
}
