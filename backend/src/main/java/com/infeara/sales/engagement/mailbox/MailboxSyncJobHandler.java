package com.infeara.sales.engagement.mailbox;

import com.infeara.sales.automation.job.Job;
import com.infeara.sales.automation.job.JobHandler;
import com.infeara.sales.common.json.JsonCodec;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Durable handler for {@code MAILBOX_SYNC}. Idempotent by construction: a re-run refetches from the
 * persisted UID cursor and the {@code inbound_key} unique index rejects anything already stored.
 */
@Component
public class MailboxSyncJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(MailboxSyncJobHandler.class);

    private final MailboxSyncService syncService;
    private final JsonCodec jsonCodec;

    public MailboxSyncJobHandler(MailboxSyncService syncService, JsonCodec jsonCodec) {
        this.syncService = syncService;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public String jobType() {
        return "MAILBOX_SYNC";
    }

    @Override
    public void execute(Job job) {
        Map<String, Object> payload = jsonCodec.readMap(job.getPayloadJson());
        Object accountId = payload.get("accountId");
        if (accountId == null) {
            throw new IllegalArgumentException("MAILBOX_SYNC job requires an accountId");
        }
        MailboxDtos.SyncResult result = syncService.sync(String.valueOf(accountId));
        log.info("mailbox_sync_job job={} account={} fetched={} persisted={}", job.getId(), accountId,
                result.fetched(), result.persisted());
    }
}
