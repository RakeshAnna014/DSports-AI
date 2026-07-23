package com.dsports.order.infrastructure.order.persistence.repository;

import com.dsports.order.infrastructure.order.persistence.entity.OrderItemEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface SpringR2dbcOrderItemRepository extends R2dbcRepository<OrderItemEntity, UUID> {

    Flux<OrderItemEntity> findByOrderId(UUID orderId);

    Mono<Void> deleteByOrderId(UUID orderId);
}
