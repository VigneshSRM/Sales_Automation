package com.infeara.sales.crm.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "tasks")
@Getter
@Setter
@NoArgsConstructor
public class Task {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "owner_user_id", length = 36, nullable = false)
    private String ownerUserId;

    @Column(name = "title", length = 300, nullable = false)
    private String title;

    @Column(name = "description", length = 4000)
    private String description;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "status", length = 20, nullable = false)
    private String status = "OPEN";

    @Column(name = "priority", length = 10, nullable = false)
    private String priority = "NORMAL";

    @Column(name = "related_type", length = 30)
    private String relatedType;

    @Column(name = "related_id", length = 36)
    private String relatedId;

    @Column(name = "reminder_policy", length = 40, nullable = false)
    private String reminderPolicy = "NONE";

    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completion_note", length = 2000)
    private String completionNote;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;
}
