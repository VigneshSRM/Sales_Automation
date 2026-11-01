package com.infeara.sales.automation.job;

/**
 * Executes one durable job type. Implementations must be idempotent and must not
 * assume they run exactly once (lease expiry can cause re-delivery).
 */
public interface JobHandler {

    String jobType();

    void execute(Job job) throws Exception;
}
