package com.dsports.shared.application.outbox;

import reactor.core.publisher.Mono;

/**
 * Records follow-up work (notifications, shipment creation) inside the caller's transaction.
 * A background dispatcher delivers it with retries, so the work is never lost when the process
 * crashes after commit and never performed when the business transaction rolls back.
 */
public interface OutboxWriter {

    Mono<Void> enqueue(OutboxMessage message);
}
