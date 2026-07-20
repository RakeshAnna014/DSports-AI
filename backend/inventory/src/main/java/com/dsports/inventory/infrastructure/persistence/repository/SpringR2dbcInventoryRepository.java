package com.dsports.inventory.infrastructure.persistence.repository;

import com.dsports.inventory.infrastructure.persistence.entity.InventoryEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SpringR2dbcInventoryRepository extends R2dbcRepository<InventoryEntity, UUID> {
    Mono<InventoryEntity> findByProductIdAndWarehouseId(UUID productId, UUID warehouseId);
    Flux<InventoryEntity> findByProductId(UUID productId);
    Mono<Boolean> existsByProductIdAndWarehouseId(UUID productId, UUID warehouseId);
}
