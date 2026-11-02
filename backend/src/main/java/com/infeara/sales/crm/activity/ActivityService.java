package com.infeara.sales.crm.activity;

import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.crm.dto.WorkDtos;
import com.infeara.sales.identity.User;
import com.infeara.sales.identity.UserRepository;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Unified record timeline (FR-ACT-001, FR-INBOX-004). */
@Service
public class ActivityService {

    private final ActivityRepository activities;
    private final ActivityRecorder recorder;
    private final UserRepository users;
    private final CapabilityEvaluator capabilities;
    private final Clock clock;

    public ActivityService(ActivityRepository activities, ActivityRecorder recorder, UserRepository users,
                           CapabilityEvaluator capabilities, Clock clock) {
        this.activities = activities;
        this.recorder = recorder;
        this.users = users;
        this.capabilities = capabilities;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkDtos.ActivityView> timeline(CurrentPrincipal principal, String relatedType,
                                                       String relatedId, int page, int size) {
        capabilities.require(principal, "activities.read");
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<Activity> result = activities.findByWorkspaceIdAndRelatedTypeAndRelatedIdOrderByOccurredAtDesc(
                principal.workspaceId(), relatedType.toUpperCase(), relatedId, pageable);
        List<WorkDtos.ActivityView> items = result.getContent().stream()
                .map(activity -> new WorkDtos.ActivityView(Ids.parse(activity.getId()), activity.getType(),
                        activity.getRelatedType(), activity.getRelatedId(), actorName(activity.getActorUserId()),
                        activity.getSubject(), activity.getBody(), activity.getOccurredAt()))
                .toList();
        return PageResponse.of(items, pageable.getPageNumber(), pageable.getPageSize(), result.getTotalElements(),
                "occurredAt:desc", List.of());
    }

    @Transactional
    public WorkDtos.ActivityView create(CurrentPrincipal principal, WorkDtos.CreateActivityRequest request) {
        capabilities.require(principal, "activities.create");
        Activity activity = recorder.record(principal, request.relatedType().toUpperCase(), request.relatedId(),
                request.type(), request.subject(), request.body(),
                Map.of("recordedBy", principal.userId()));
        return new WorkDtos.ActivityView(Ids.parse(activity.getId()), activity.getType(),
                activity.getRelatedType(), activity.getRelatedId(), principal.displayName(),
                activity.getSubject(), activity.getBody(), activity.getOccurredAt());
    }

    private String actorName(String userId) {
        return userId == null ? "System"
                : users.findById(userId).map(User::getDisplayName).orElse("System");
    }

    public Clock clock() {
        return clock;
    }
}
