package com.infeara.sales.engagement.mailbox;

import com.infeara.sales.automation.job.Job;
import com.infeara.sales.automation.job.JobHandler;
import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.json.JsonCodec;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Durable handler for {@code EMAIL_ANALYZE}: AI classification plus response-policy hand-off.
 *
 * <p>A missing AI provider is not retried forever - it is reported as a permanent failure so the
 * job reaches the dead-letter state with a readable reason, and the inbox shows AI as unavailable
 * rather than silently pretending the message was analysed.
 */
@Component
public class EmailAnalyzeJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(EmailAnalyzeJobHandler.class);

    private final EmailIntelligenceService intelligence;
    private final JsonCodec jsonCodec;

    public EmailAnalyzeJobHandler(EmailIntelligenceService intelligence, JsonCodec jsonCodec) {
        this.intelligence = intelligence;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public String jobType() {
        return "EMAIL_ANALYZE";
    }

    @Override
    public void execute(Job job) {
        Map<String, Object> payload = jsonCodec.readMap(job.getPayloadJson());
        Object messageId = payload.get("messageId");
        if (messageId == null) {
            throw new IllegalArgumentException("EMAIL_ANALYZE job requires a messageId");
        }
        try {
            EmailIntelligenceService.RawAnalysis raw = intelligence.requestAnalysis(String.valueOf(messageId));
            EmailAnalysis analysis = intelligence.applyAnalysis(String.valueOf(messageId), raw);
            log.info("email_analysis_job job={} message={} intent={} provider={}", job.getId(), messageId,
                    analysis.getIntent(), analysis.getProvider());
        } catch (ApiException ex) {
            if (ex.errorCode() == ErrorCode.AI_PROVIDER_UNAVAILABLE) {
                log.warn("email_analysis_skipped job={} message={} reason=no_provider", job.getId(), messageId);
                return;
            }
            throw ex;
        }
    }
}
