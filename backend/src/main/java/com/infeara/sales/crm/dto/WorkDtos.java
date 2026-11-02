package com.infeara.sales.crm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Activity and task contracts. */
public final class WorkDtos {

    private WorkDtos() {
    }

    public record CreateActivityRequest(
            @NotBlank @Pattern(regexp = "CALL|NOTE|MEETING|EMAIL") String type,
            @NotBlank @Pattern(regexp = "LEAD|ACCOUNT|CONTACT|OPPORTUNITY") String relatedType,
            @NotBlank String relatedId,
            @Size(max = 300) String subject,
            @Size(max = 8000) String body) {
    }

    public record ActivityView(UUID id, String type, String relatedType, String relatedId, String actorName,
                               String subject, String body, Instant occurredAt) {
    }

    public record CreateTaskRequest(
            @NotBlank @Size(max = 300) String title,
            @Size(max = 4000) String description,
            Instant dueAt,
            @Pattern(regexp = "LOW|NORMAL|HIGH|URGENT") String priority,
            @Pattern(regexp = "LEAD|ACCOUNT|CONTACT|OPPORTUNITY") String relatedType,
            String relatedId,
            UUID ownerUserId) {
    }

    public record CompleteTaskRequest(@Size(max = 2000) String completionNote) {
    }

    public record TaskView(UUID id, String title, String description, Instant dueAt, String status,
                           String priority, String relatedType, String relatedId, String ownerName,
                           boolean overdue, Instant completedAt, long rowVersion) {
    }
}
