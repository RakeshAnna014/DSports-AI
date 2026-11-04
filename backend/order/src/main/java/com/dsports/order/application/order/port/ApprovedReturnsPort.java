package com.dsports.order.application.order.port;

import reactor.core.publisher.Flux;

import java.util.Collection;
import java.util.UUID;

/** Approved returned quantity per order and product. Implemented by the returns module (composed in the bootstrap module): the order module never reads another module's tables. */
public interface ApprovedReturnsPort {

    record ApprovedReturn(UUID orderId, UUID productId, long quantity) {}

    /** Quantities of APPROVED return requests only (requested and rejected ones are not returns), summed per order and product. */
    Flux<ApprovedReturn> approvedFor(Collection<UUID> orderIds);
}
