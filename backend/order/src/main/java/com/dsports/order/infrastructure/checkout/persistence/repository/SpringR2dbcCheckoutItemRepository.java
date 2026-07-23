package com.dsports.order.infrastructure.checkout.persistence.repository;

import com.dsports.order.infrastructure.checkout.persistence.entity.CheckoutItemEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface SpringR2dbcCheckoutItemRepository extends R2dbcRepository<CheckoutItemEntity, UUID> {

    Flux<CheckoutItemEntity> findByCheckoutId(UUID checkoutId);

    Mono<Void> deleteByCheckoutId(UUID checkoutId);
}
