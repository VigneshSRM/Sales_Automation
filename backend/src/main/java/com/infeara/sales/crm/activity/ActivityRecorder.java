package com.infeara.sales.crm.activity;

import com.infeara.sales.common.json.JsonCodec;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.security.CurrentPrincipal;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends timeline events to a CRM record (FR-ACT-001). Timeline entries are separate
 * from the immutable security audit log: they are business history and may be filtered
 * or summarised in the UI, while audit records are the compliance record.
 */
@Service
public class ActivityRecorder {

    private final ActivityRepository repository;
    private final JsonCodec jsonCodec;
    private final Clock clock;

    public ActivityRecorder(ActivityRepository repository, JsonCodec jsonCodec, Clock clock) {
        this.repository = repository;
        this.jsonCodec = jsonCodec;
        this.clock = clock;
    }

    /**
     * System-authored timeline entry (worker, webhook, automation). Used where no human
     * is authenticated, e.g. an email delivered by the sequence engine.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Activity recordSystem(String workspaceId, String relatedType, String relatedId, String type,
                                 String subject, String body, Map<String, Object> payload) {
        Activity activity = new Activity();
        activity.setId(Ids.newId());
        activity.setWorkspaceId(workspaceId);
        activity.setType(type);
        activity.setRelatedType(relatedType);
        activity.setRelatedId(relatedId);
        activity.setActorType("SYSTEM");
        activity.setSubject(subject);
        activity.setBody(body);
        activity.setPayloadJson(payload == null || payload.isEmpty() ? null : jsonCodec.write(payload));
        activity.setOccurredAt(clock.instant());
        return repository.save(activity);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Activity record(CurrentPrincipal actor, String relatedType, String relatedId, String type,
                           String subject, String body, Map<String, Object> payload) {
        Activity activity = new Activity();
        activity.setId(Ids.newId());
        activity.setWorkspaceId(actor.workspaceId());
        activity.setType(type);
        activity.setRelatedType(relatedType);
        activity.setRelatedId(relatedId);
        activity.setActorType("USER");
        activity.setActorUserId(actor.userId());
        activity.setSubject(subject);
        activity.setBody(body);
        activity.setPayloadJson(payload == null || payload.isEmpty() ? null : jsonCodec.write(payload));
        activity.setOccurredAt(clock.instant());
        return repository.save(activity);
    }
}
