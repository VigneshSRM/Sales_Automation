package com.infeara.sales.engagement.mailbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Validated AI analysis of one inbound message (addendum 3.3).
 *
 * <p>Only schema-validated output is stored with {@code validated = true}. The provider, model and
 * prompt version are recorded so a policy decision can be reproduced; the raw provider response is
 * never persisted.
 */
@Entity
@Table(name = "email_analysis")
@Getter
@Setter
@NoArgsConstructor
public class EmailAnalysis {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "message_id", length = 36, nullable = false)
    private String messageId;

    @Column(name = "intent", length = 40, nullable = false)
    private String intent;

    @Column(name = "sub_intent", length = 80)
    private String subIntent;

    @Column(name = "sentiment", length = 20)
    private String sentiment;

    @Column(name = "urgency", length = 20)
    private String urgency;

    @Column(name = "buying_signal", length = 20)
    private String buyingSignal;

    @Column(name = "confidence", nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "extracted_json")
    private String extractedJson;

    @Column(name = "questions_json")
    private String questionsJson;

    @Column(name = "risks_json")
    private String risksJson;

    @Column(name = "recommended_action", length = 500)
    private String recommendedAction;

    @Column(name = "reply_draft")
    private String replyDraft;

    @Column(name = "evidence_json")
    private String evidenceJson;

    @Column(name = "provider", length = 40, nullable = false)
    private String provider;

    @Column(name = "model", length = 120, nullable = false)
    private String model;

    @Column(name = "prompt_version", length = 40, nullable = false)
    private String promptVersion;

    @Column(name = "token_input")
    private Integer tokenInput;

    @Column(name = "token_output")
    private Integer tokenOutput;

    @Column(name = "cost_usd")
    private BigDecimal costUsd;

    @Column(name = "validated", nullable = false)
    private boolean validated;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
