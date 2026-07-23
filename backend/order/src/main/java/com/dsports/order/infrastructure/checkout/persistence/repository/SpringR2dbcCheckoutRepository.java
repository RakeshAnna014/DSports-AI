package com.dsports.order.infrastructure.checkout.persistence.repository;

import com.dsports.order.infrastructure.checkout.persistence.entity.CheckoutEntity;
import com.dsports.order.infrastructure.checkout.persistence.entity.CheckoutItemEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface SpringR2dbcCheckoutRepository extends R2dbcRepository<CheckoutEntity, UUID> {

    Mono<CheckoutEntity> findByCustomerIdAndStatusNotIn(UUID customerId, String... statuses);

    Mono<Boolean> existsByCustomerIdAndStatusNotIn(UUID customerId, String... statuses);

    @Query("SELECT * FROM checkout_items WHERE checkout_id = :checkoutId ORDER BY created_at")
    Flux<CheckoutItemEntity> findItemsByCheckoutId(UUID checkoutId);
}
