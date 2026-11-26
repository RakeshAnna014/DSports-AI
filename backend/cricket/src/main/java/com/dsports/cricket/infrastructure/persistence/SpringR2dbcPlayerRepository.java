package com.dsports.cricket.infrastructure.persistence;

import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SpringR2dbcPlayerRepository extends R2dbcRepository<PlayerEntity, UUID> {

    Flux<PlayerEntity> findByCreatedByOrCustomerIdOrderByNameAsc(UUID createdBy, UUID customerId);

    Mono<PlayerEntity> findByCustomerId(UUID customerId);

    Flux<PlayerEntity> findByPhoneAndCustomerIdIsNullOrderByCreatedAtAsc(String phone);
}
