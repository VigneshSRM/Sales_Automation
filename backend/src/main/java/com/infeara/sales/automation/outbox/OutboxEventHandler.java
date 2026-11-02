package com.infeara.sales.automation.outbox;

/**
 * Consumer of one or more outbox event types. Handlers run inside the relay's
 * transaction and must be idempotent: an event may be re-delivered after a worker
 * crash when its lease expires.
 */
public interface OutboxEventHandler {

    /** Event types this handler consumes, e.g. {@code LEAD_CREATED}. */
    java.util.Set<String> eventTypes();

    void handle(OutboxEvent event);
}
