package com.dsports.inventory.application.port;

import com.dsports.inventory.domain.model.ProductId;
import com.dsports.inventory.domain.model.StockReservation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface StockReservationRepository {

    Flux<StockReservation> findByOrderId(UUID orderId);

    Mono<StockReservation> findByOrderIdAndProductId(UUID orderId, ProductId productId);

    Mono<Void> save(StockReservation reservation);
}
