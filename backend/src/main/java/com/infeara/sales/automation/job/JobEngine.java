package com.infeara.sales.automation.job;

import com.infeara.sales.common.json.JsonCodec;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable job engine (ADR-003). No Redis, no broker: rows in {@code jobs} are claimed
 * with an atomic conditional UPDATE, executed by {@link JobHandler} beans, retried with
 * exponential backoff and moved to a dead-letter state after the attempt budget.
 * A crashed worker's lease expires and the job is reclaimed; handlers must therefore
 * be idempotent (idempotency keys protect external side effects).
 */
@Service
public class JobEngine {

    private static final Logger log = LoggerFactory.getLogger(JobEngine.class);

    private final JobRepository jobs;
    private final JobExecutionRepository executions;
    private final JsonCodec jsonCodec;
    private final AppProperties properties;
    private final Clock clock;
    private final Map<String, JobHandler> handlers = new ConcurrentHashMap<>();
    private final ObjectProvider<JobHandler> jobHandlers;
    private final String workerId;
    private volatile boolean handlersResolved;

    /**
     * Handlers are taken as an {@link ObjectProvider} rather than a {@code List} on purpose.
     * Several handlers need services that themselves need the engine (a mailbox handler needs
     * the sync service, which enqueues follow-up jobs), and a constructor-injected list forces
     * every handler to be built while the engine is still being built — an unresolvable cycle.
     * Resolving lazily on the first dispatch keeps construction order free.
     */
    public JobEngine(JobRepository jobs, JobExecutionRepository executions, JsonCodec jsonCodec,
                     AppProperties properties, Clock clock, ObjectProvider<JobHandler> jobHandlers) {
        this.jobs = jobs;
        this.executions = executions;
        this.jsonCodec = jsonCodec;
        this.properties = properties;
        this.clock = clock;
        this.workerId = (properties.getWorker().getInstanceId() == null
                ? "worker-" + UUID.randomUUID().toString().substring(0, 8)
                : properties.getWorker().getInstanceId()) + "-jobs";
        this.jobHandlers = jobHandlers;
    }

    private Map<String, JobHandler> handlers() {
        if (!handlersResolved) {
            synchronized (this) {
                if (!handlersResolved) {
                    jobHandlers.orderedStream().forEach(h -> handlers.put(h.jobType(), h));
                    handlersResolved = true;
                    log.info("job_engine_registered worker={} handlers={}", workerId, handlers.keySet());
                }
            }
        }
        return handlers;
    }

    /** Enqueues a job unless one with the same idempotency key already exists. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String enqueue(String workspaceId, String jobType, String idempotencyKey, Object payload,
                          int priority) {
        if (idempotencyKey != null && jobs.findByIdempotencyKey(idempotencyKey).isPresent()) {
            return jobs.findByIdempotencyKey(idempotencyKey).map(Job::getId).orElse(null);
        }
        Job job = new Job();
        job.setId(Ids.newId());
        job.setWorkspaceId(workspaceId);
        job.setJobType(jobType);
        job.setPayloadJson(jsonCodec.write(payload == null ? Map.of() : payload));
        job.setIdempotencyKey(idempotencyKey);
        job.setPriority(priority);
        job.setMaxAttempts(properties.getWorker().getJobMaxAttempts());
        job.setAvailableAt(clock.instant());
        job.setCorrelationId(com.infeara.sales.common.web.CorrelationIdFilter.currentRequestId());
        jobs.save(job);
        return job.getId();
    }

    @Scheduled(fixedDelayString = "${app.worker.tick-interval-ms:5000}", initialDelay = 20000)
    public void poll() {
        if (!properties.getWorker().isEnabled()) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException ex) {
            log.error("job_poll_failed", ex);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runOnce() {
        Instant now = clock.instant();
        LocalDateTime nowLdt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        List<Job> claimable = jobs.findClaimable(nowLdt, properties.getWorker().getJobsBatchSize());
        for (Job candidate : claimable) {
            executeClaimed(candidate, now);
        }
    }

    private void executeClaimed(Job candidate, Instant now) {
        LocalDateTime leaseUntil = LocalDateTime.ofInstant(
                now.plusSeconds(properties.getWorker().getJobLeaseSeconds()), ZoneOffset.UTC);
        if (jobs.claim(candidate.getId(), workerId, leaseUntil) != 1) {
            return;
        }
        Job job = jobs.findById(candidate.getId()).orElse(null);
        if (job == null) {
            return;
        }
        JobExecution execution = new JobExecution();
        execution.setId(Ids.newId());
        execution.setJobId(job.getId());
        execution.setAttemptNo(job.getAttemptCount());
        execution.setWorkerId(workerId);
        execution.setStatus("RUNNING");
        executions.save(execution);
        Instant started = clock.instant();
        JobHandler handler = handlers().get(job.getJobType());
        try {
            if (handler == null) {
                throw new IllegalStateException("NO_HANDLER_REGISTERED:" + job.getJobType());
            }
            handler.execute(job);
            job.setStatus("COMPLETED");
            job.setCompletedAt(clock.instant());
            job.setLockedBy(null);
            job.setLockedUntil(null);
            job.setLastError(null);
            jobs.save(job);
            execution.setStatus("SUCCEEDED");
        } catch (Exception ex) {
            int attempts = job.getAttemptCount();
            String message = (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            job.setLastError(message.substring(0, Math.min(message.length(), 1990)));
            if (attempts >= job.getMaxAttempts()) {
                job.setStatus("DEAD");
                log.error("job_dead id={} type={} attempts={}", job.getId(), job.getJobType(), attempts, ex);
            } else {
                long delay = Math.min(properties.getWorker().getJobBackoffBaseSeconds()
                        * (1L << Math.min(attempts, 10)), properties.getWorker().getJobBackoffCapSeconds());
                job.setStatus("RETRY");
                job.setAvailableAt(clock.instant().plus(Duration.ofSeconds(delay)));
                log.warn("job_retry id={} type={} attempt={} delay={}s", job.getId(), job.getJobType(), attempts,
                        delay);
            }
            job.setLockedBy(null);
            job.setLockedUntil(null);
            jobs.save(job);
            execution.setStatus("FAILED");
            execution.setErrorCode("JOB_EXECUTION_FAILED");
            execution.setErrorMessage(job.getLastError());
        } finally {
            execution.setFinishedAt(clock.instant());
            execution.setDurationMs(Duration.between(started, clock.instant()).toMillis());
            executions.save(execution);
        }
    }

    @Transactional
    public void replay(String workspaceId, String jobId, String actorUserId, String actorEmail) {
        Job job = jobs.findByIdAndWorkspaceId(jobId, workspaceId)
                .orElseThrow(() -> new com.infeara.sales.common.error.ApiException(
                        com.infeara.sales.common.error.ErrorCode.JOB_NOT_FOUND, "Job not found"));
        if (!"DEAD".equals(job.getStatus()) && !"FAILED".equals(job.getStatus())) {
            throw new com.infeara.sales.common.error.ApiException(
                    com.infeara.sales.common.error.ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Only dead or failed jobs can be replayed");
        }
        job.setStatus("PENDING");
        job.setAttemptCount(0);
        job.setAvailableAt(clock.instant());
        job.setLastError(null);
        jobs.save(job);
        log.info("job_replayed id={} actor={}", jobId, actorUserId);
    }
}
