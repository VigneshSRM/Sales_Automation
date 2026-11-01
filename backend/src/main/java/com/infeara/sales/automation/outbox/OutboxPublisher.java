package com.infeara.sales.automation.outbox;

import com.infeara.sales.common.json.JsonCodec;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.common.web.CorrelationIdFilter;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes a domain event into the transactional outbox. Callers must invoke this
 * inside the business transaction so that the event and the state change commit
 * together (BR-DATA-007); if the transaction rolls back the event disappears.
 */
@Service
public class OutboxPublisher {

    private final OutboxEventRepository repository;
    private final JsonCodec jsonCodec;

    public OutboxPublisher(OutboxEventRepository repository, JsonCodec jsonCodec) {
        this.repository = repository;
        this.jsonCodec = jsonCodec;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String workspaceId, String aggregateType, String aggregateId, String eventType,
                        Object payload) {
        OutboxEvent event = new OutboxEvent();
        event.setId(Ids.newId());
        event.setWorkspaceId(workspaceId);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setPayloadJson(jsonCodec.write(payload));
        event.setAvailableAt(Instant.now());
        event.setCorrelationId(CorrelationIdFilter.currentRequestId());
        repository.save(event);
    }
}
