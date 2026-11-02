package com.infeara.sales.engagement.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Append-only delivery evidence for a message (used for reply/bounce handling). */
@Entity
@Table(name = "email_events")
@Getter
@Setter
@NoArgsConstructor
public class EmailEvent {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "message_id", length = 36, nullable = false)
    private String messageId;

    @Column(name = "event_type", length = 40, nullable = false)
    private String eventType;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
