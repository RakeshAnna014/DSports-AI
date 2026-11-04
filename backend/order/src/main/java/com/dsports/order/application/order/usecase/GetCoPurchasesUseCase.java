package com.dsports.order.application.order.usecase;

import com.dsports.order.application.order.port.CoPurchasePort;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * "Frequently bought together" evidence. The threshold is enforced here, never by the caller, so a single customer's basket can
 * never be exposed as a pattern: a pair is only reported when enough distinct orders back it.
 */
public class GetCoPurchasesUseCase {

    private final CoPurchasePort port;
    private final int minimumOrders;

    public GetCoPurchasesUseCase(CoPurchasePort port, int minimumOrders) {
        this.port = port;
        this.minimumOrders = Math.max(2, minimumOrders);
    }

    public int minimumOrders() {
        return minimumOrders;
    }

    public Flux<CoPurchasePort.CoPurchase> execute(UUID productId, int requestedMinOrders, int limit) {
        return port.coPurchased(productId, Math.max(minimumOrders, requestedMinOrders), Math.min(Math.max(1, limit), 20));
    }
}
