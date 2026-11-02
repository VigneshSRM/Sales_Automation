package com.infeara.sales.crm.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Timeline event for a CRM record. Distinct from the immutable security audit log. */
@Entity
@Table(name = "activities")
@Getter
@Setter
@NoArgsConstructor
public class Activity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "type", length = 32, nullable = false)
    private String type;

    @Column(name = "related_type", length = 30, nullable = false)
    private String relatedType;

    @Column(name = "related_id", length = 36, nullable = false)
    private String relatedId;

    @Column(name = "actor_type", length = 16, nullable = false)
    private String actorType = "USER";

    @Column(name = "actor_user_id", length = 36)
    private String actorUserId;

    @Column(name = "subject", length = 300)
    private String subject;

    @Column(name = "body")
    private String body;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
