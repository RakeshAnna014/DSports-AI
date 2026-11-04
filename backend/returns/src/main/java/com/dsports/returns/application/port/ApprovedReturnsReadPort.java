package com.dsports.returns.application.port;

import reactor.core.publisher.Flux;

import java.util.Collection;
import java.util.UUID;

/** Read-only view of approved return quantities, for reporting modules (composed in the bootstrap module). Requested and rejected returns are not counted. */
public interface ApprovedReturnsReadPort {

    record Approved(UUID orderId, UUID productId, long quantity) {}

    Flux<Approved> approvedFor(Collection<UUID> orderIds);
}
