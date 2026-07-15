package com.dsports.identity.infrastructure.persistence.repository;

import com.dsports.identity.infrastructure.persistence.entity.AddressEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SpringR2dbcAddressRepository extends R2dbcRepository<AddressEntity, UUID> {
    Flux<AddressEntity> findByCustomerId(UUID customerId);
    Mono<Void> deleteByCustomerIdAndId(UUID customerId, UUID id);
}
