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

    @Query("SELECT * FROM checkouts WHERE customer_id = :customerId AND status <> 'EXPIRED' AND status <> 'CANCELLED' AND expires_at > NOW() LIMIT 1")
    Mono<CheckoutEntity> findActiveByCustomerId(UUID customerId);

    @Query("SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END FROM checkouts WHERE customer_id = :customerId AND status <> 'EXPIRED' AND status <> 'CANCELLED' AND expires_at > NOW()")
    Mono<Boolean> existsActiveCheckout(UUID customerId);

    @Query("SELECT * FROM checkout_items WHERE checkout_id = :checkoutId ORDER BY created_at")
    Flux<CheckoutItemEntity> findItemsByCheckoutId(UUID checkoutId);
}
