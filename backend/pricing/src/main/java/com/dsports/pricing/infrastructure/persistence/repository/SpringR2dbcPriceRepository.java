package com.dsports.pricing.infrastructure.persistence.repository;

import com.dsports.pricing.infrastructure.persistence.entity.PriceEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.r2dbc.repository.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SpringR2dbcPriceRepository extends R2dbcRepository<PriceEntity, UUID> {

    Flux<PriceEntity> findByProductIdOrderByCreatedAtDesc(UUID productId);

    Mono<Boolean> existsByProductIdAndCurrencyAndStatus(UUID productId, String currency, String status);

    @Query("UPDATE prices SET status = 'ARCHIVED', updated_at = NOW() " +
           "WHERE product_id = :productId AND currency = :currency " +
           "AND status = 'ACTIVE' AND id != :excludeId")
    Mono<Integer> deactivateActivePrices(UUID productId, String currency, UUID excludeId);
}
