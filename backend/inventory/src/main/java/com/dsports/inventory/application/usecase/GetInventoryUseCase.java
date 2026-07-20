package com.dsports.inventory.application.usecase;

import com.dsports.inventory.application.port.InventoryRepository;
import com.dsports.inventory.application.result.InventoryResult;
import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import com.dsports.inventory.domain.model.InventoryId;
import reactor.core.publisher.Mono;

public class GetInventoryUseCase {

    private final InventoryRepository inventoryRepository;

    public GetInventoryUseCase(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    public Mono<InventoryResult> execute(InventoryId id) {
        return inventoryRepository.findById(id)
                .switchIfEmpty(Mono.error(new InventoryDomainException(InventoryErrorCode.INVENTORY_NOT_FOUND,
                        "Inventory not found: " + id)))
                .map(InventoryResultMapper::toResult);
    }
}
