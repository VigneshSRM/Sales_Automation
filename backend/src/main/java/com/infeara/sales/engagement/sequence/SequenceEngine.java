package com.infeara.sales.engagement.sequence;

import com.infeara.sales.config.AppProperties;
import com.infeara.sales.workspace.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled driver for due cadence steps. Each workspace is processed independently so
 * one workspace's SMTP outage cannot starve another's follow-ups.
 */
@Component
public class SequenceEngine {

    private static final Logger log = LoggerFactory.getLogger(SequenceEngine.class);
    private static final int PER_WORKSPACE_LIMIT = 25;

    private final SequenceService sequenceService;
    private final WorkspaceRepository workspaces;
    private final AppProperties properties;

    public SequenceEngine(SequenceService sequenceService, WorkspaceRepository workspaces,
                          AppProperties properties) {
        this.sequenceService = sequenceService;
        this.workspaces = workspaces;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.worker.tick-interval-ms:5000}", initialDelay = 15000)
    public void tick() {
        if (!properties.getWorker().isEnabled()) {
            return;
        }
        workspaces.findAll().forEach(workspace -> {
            try {
                SequenceService.EngineResult result = sequenceService.runDue(workspace.getId(),
                        PER_WORKSPACE_LIMIT);
                if (result.processed() > 0) {
                    log.info("Sequence engine workspace={} processed={} queued={} stopped={}",
                            workspace.getSlug(), result.processed(), result.sent(), result.stopped());
                }
            } catch (RuntimeException ex) {
                log.error("Sequence engine failed for workspace {}", workspace.getSlug(), ex);
            }
        });
    }
}
