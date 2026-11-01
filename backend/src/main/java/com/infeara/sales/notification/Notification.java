package com.infeara.sales.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "recipient_user_id", length = 36, nullable = false)
    private String recipientUserId;

    @Column(name = "type", length = 60, nullable = false)
    private String type;

    @Column(name = "severity", length = 16, nullable = false)
    private String severity = "INFO";

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "body", length = 2000)
    private String body;

    @Column(name = "link", length = 400)
    private String link;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "dedupe_key", length = 200)
    private String dedupeKey;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
