package com.infeara.sales.automation.outbox;

import com.infeara.sales.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database-backed outbox relay (ADR-003). Polls claimable rows, claims each one with
 * an atomic conditional UPDATE, dispatches to registered handlers and records the
 * outcome with exponential backoff and a dead-letter state.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final AppProperties properties;
    private final Clock clock;
    private final Map<String, OutboxEventHandler> handlersByType = new ConcurrentHashMap<>();
    private final String workerId;

    public OutboxRelay(OutboxEventRepository repository, AppProperties properties, Clock clock,
                       List<OutboxEventHandler> handlers) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.workerId = (properties.getWorker().getInstanceId() == null
                ? "outbox-" + UUID.randomUUID().toString().substring(0, 8)
                : properties.getWorker().getInstanceId()) + "-outbox";
        for (OutboxEventHandler handler : handlers) {
            for (String type : handler.eventTypes()) {
                handlersByType.put(type, handler);
            }
        }
        log.info("outbox_relay_registered handlers={} types={}", handlers.size(), handlersByType.keySet());
    }

    @Scheduled(fixedDelayString = "${app.worker.tick-interval-ms:5000}", initialDelay = 15000)
    public void poll() {
        if (!properties.getWorker().isEnabled()) {
            return;
        }
        try {
            relayOnce();
        } catch (RuntimeException ex) {
            log.error("outbox_poll_failed", ex);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void relayOnce() {
        Instant now = clock.instant();
        LocalDateTime nowLdt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        List<OutboxEvent> candidates = repository.findClaimable(nowLdt, properties.getWorker().getOutboxBatchSize());
        for (OutboxEvent candidate : candidates) {
            processCandidate(candidate, now);
        }
    }

    private void processCandidate(OutboxEvent candidate, Instant now) {
        LocalDateTime leaseUntil = LocalDateTime.ofInstant(
                now.plusSeconds(properties.getWorker().getOutboxLeaseSeconds()), ZoneOffset.UTC);
        if (repository.claim(candidate.getId(), workerId, leaseUntil) != 1) {
            return; // another instance claimed it
        }
        OutboxEvent event = repository.findById(candidate.getId()).orElse(null);
        if (event == null) {
            return;
        }
        Set<String> types = handlersByType.keySet();
        OutboxEventHandler handler = handlersByType.get(event.getEventType());
        try {
            if (handler == null) {
                // No consumer yet in this release: the event is retained as DEAD so it is
                // visible on the admin failed-jobs screen instead of silently disappearing.
                event.setStatus("DEAD");
                event.setLastError("NO_HANDLER_REGISTERED:" + event.getEventType()
                        + " (registered: " + types + ")");
                repository.save(event);
                log.warn("outbox_no_handler type={} id={}", event.getEventType(), event.getId());
                return;
            }
            handler.handle(event);
            event.setStatus("COMPLETED");
            event.setProcessedAt(clock.instant());
            event.setLockedBy(null);
            event.setLockedUntil(null);
            event.setLastError(null);
            repository.save(event);
        } catch (RuntimeException ex) {
            applyFailure(event, ex);
        }
    }

    private void applyFailure(OutboxEvent event, RuntimeException ex) {
        int attempts = event.getAttemptCount();
        String message = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        event.setLastError(message == null ? "unknown" : message.substring(0, Math.min(message.length(), 1990)));
        if (attempts >= event.getMaxAttempts()) {
            event.setStatus("DEAD");
            log.error("outbox_dead id={} type={}", event.getId(), event.getEventType(), ex);
        } else {
            long delay = Math.min(properties.getWorker().getJobBackoffBaseSeconds()
                    * (1L << Math.min(attempts, 10)), properties.getWorker().getJobBackoffCapSeconds());
            event.setStatus("RETRY");
            event.setAvailableAt(clock.instant().plus(Duration.ofSeconds(delay)));
            log.warn("outbox_retry id={} attempt={} delay={}s", event.getId(), attempts, delay);
        }
        event.setLockedBy(null);
        event.setLockedUntil(null);
        repository.save(event);
    }
}
