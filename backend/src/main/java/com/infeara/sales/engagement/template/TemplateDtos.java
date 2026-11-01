package com.infeara.sales.engagement.template;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TemplateDtos {

    private TemplateDtos() {
    }

    public record CreateTemplateRequest(
            @NotBlank @Size(max = 160) String name,
            @Pattern(regexp = "OUTREACH|FOLLOW_UP|MEETING|PROPOSAL|NURTURE|RENEWAL") String purpose,
            @NotBlank @Size(max = 300) String subject,
            @NotBlank @Size(max = 20000) String body) {
    }

    public record UpdateTemplateRequest(
            @Size(max = 160) String name,
            @Pattern(regexp = "OUTREACH|FOLLOW_UP|MEETING|PROPOSAL|NURTURE|RENEWAL") String purpose,
            @Size(max = 300) String subject,
            @Size(max = 20000) String body,
            Long expectedRowVersion) {
    }

    public record TemplateView(UUID id, String name, String purpose, String subject, String body,
                               List<String> mergeFields, String status, int version, Instant approvedAt,
                               Instant updatedAt, long rowVersion) {
    }

    public record PreviewRequest(@NotBlank String subject, @NotBlank String body) {
    }

    public record PreviewResult(String subject, String body, List<String> mergeFields,
                                List<String> sampleValues) {
    }
}
