package com.dsports.inventory.application.usecase;

import com.dsports.inventory.application.port.InventoryRepository;
import com.dsports.inventory.application.result.InventoryResult;
import reactor.core.publisher.Flux;

public class GetInventoriesUseCase {

    private final InventoryRepository inventoryRepository;

    public GetInventoriesUseCase(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    public Flux<InventoryResult> execute() {
        return inventoryRepository.findAll()
                .map(InventoryResultMapper::toResult);
    }
}
