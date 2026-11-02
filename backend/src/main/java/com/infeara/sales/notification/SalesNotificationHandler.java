package com.infeara.sales.notification;

import com.infeara.sales.automation.outbox.OutboxEvent;
import com.infeara.sales.automation.outbox.OutboxEventHandler;
import com.infeara.sales.common.json.JsonCodec;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Consumes CRM outbox events and creates in-app notifications for the record owner
 * and, for assignments, the new owner (spec §10.4 "notify sales representative").
 * Idempotent: the notification dedupe key is derived from the event id.
 */
@Component
public class SalesNotificationHandler implements OutboxEventHandler {

    private final NotificationService notifications;
    private final JsonCodec jsonCodec;

    public SalesNotificationHandler(NotificationService notifications, JsonCodec jsonCodec) {
        this.notifications = notifications;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("LEAD_CREATED", "LEAD_ASSIGNED", "LEAD_MERGED", "OPPORTUNITY_STAGE_CHANGED",
                "TASK_ASSIGNED");
    }

    @Override
    public void handle(OutboxEvent event) {
        Map<String, Object> payload = jsonCodec.readMap(event.getPayloadJson());
        String recipient = stringValue(payload.get("notifyUserId"));
        if (recipient == null) {
            return;
        }
        String title;
        String link;
        switch (event.getEventType()) {
            case "LEAD_CREATED" -> {
                title = "New lead: " + stringValue(payload.getOrDefault("companyName", "unknown company"));
                link = "/leads/" + event.getAggregateId();
            }
            case "LEAD_ASSIGNED" -> {
                title = "A lead was assigned to you";
                link = "/leads/" + event.getAggregateId();
            }
            case "LEAD_MERGED" -> {
                title = "A duplicate lead was merged into " + stringValue(payload.get("targetCompany"));
                link = "/leads/" + event.getAggregateId();
            }
            case "OPPORTUNITY_STAGE_CHANGED" -> {
                title = "Opportunity moved to " + stringValue(payload.getOrDefault("toStage", "a new stage"));
                link = "/opportunities/" + event.getAggregateId();
            }
            default -> {
                title = "Task assigned to you";
                link = "/tasks";
            }
        }
        notifications.notifyUser(event.getWorkspaceId(), recipient, event.getEventType(), "INFO", title,
                stringValue(payload.get("summary")), link, "outbox:" + event.getId());
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
