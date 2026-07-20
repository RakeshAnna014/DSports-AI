package com.dsports.inventory.application.port;

import com.dsports.inventory.domain.model.InventoryId;
import com.dsports.inventory.domain.model.InventoryItem;
import com.dsports.inventory.domain.model.ProductId;
import com.dsports.inventory.domain.model.WarehouseId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface InventoryRepository {
    Mono<InventoryItem> findById(InventoryId id);
    Mono<InventoryItem> findByProductIdAndWarehouseId(ProductId productId, WarehouseId warehouseId);
    Flux<InventoryItem> findByProductId(ProductId productId);
    Flux<InventoryItem> findAll();
    Mono<Boolean> existsByProductIdAndWarehouseId(ProductId productId, WarehouseId warehouseId);
    Mono<Void> save(InventoryItem item);
}
