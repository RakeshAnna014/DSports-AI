package com.dsports.order.infrastructure.order.persistence.repository;

import com.dsports.order.infrastructure.order.persistence.entity.OrderEntity;
import com.dsports.order.infrastructure.order.persistence.entity.OrderItemEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface SpringR2dbcOrderRepository extends R2dbcRepository<OrderEntity, UUID> {

    Mono<OrderEntity> findByOrderNumber(String orderNumber);

    Flux<OrderEntity> findByUserIdOrderByPlacedAtDesc(UUID userId);

    Mono<Boolean> existsByCheckoutId(UUID checkoutId);

    @Query("SELECT nextval('order_number_seq')")
    Mono<Long> nextOrderSequence();

    Flux<OrderEntity> findByStatusAndPlacedAtBefore(String status, java.time.Instant cutoff);

    @Query("SELECT * FROM order_items WHERE order_id = :orderId ORDER BY created_at")
    Flux<OrderItemEntity> findItemsByOrderId(UUID orderId);
}
