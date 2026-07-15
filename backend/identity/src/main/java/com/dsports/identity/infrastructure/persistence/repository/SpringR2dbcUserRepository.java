package com.dsports.identity.infrastructure.persistence.repository;

import com.dsports.identity.infrastructure.persistence.entity.CustomerEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SpringR2dbcUserRepository extends R2dbcRepository<CustomerEntity, UUID> {
    Mono<CustomerEntity> findByEmail(String email);
}
