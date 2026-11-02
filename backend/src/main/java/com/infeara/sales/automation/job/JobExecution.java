package com.infeara.sales.automation.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "job_executions")
@Getter
@Setter
@NoArgsConstructor
public class JobExecution {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "job_id", length = 36, nullable = false)
    private String jobId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "worker_id", length = 80, nullable = false)
    private String workerId;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "started_at", nullable = false, insertable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_code", length = 60)
    private String errorCode;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;
}
