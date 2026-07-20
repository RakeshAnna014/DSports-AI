package com.dsports.inventory.application.usecase;

import com.dsports.inventory.application.command.AdjustInventoryCommand;
import com.dsports.inventory.application.port.InventoryRepository;
import com.dsports.inventory.application.result.InventoryResult;
import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import com.dsports.inventory.domain.model.Quantity;
import reactor.core.publisher.Mono;

public class AdjustInventoryUseCase {

    private final InventoryRepository inventoryRepository;

    public AdjustInventoryUseCase(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    public Mono<InventoryResult> execute(AdjustInventoryCommand command) {
        return inventoryRepository.findById(command.inventoryId())
                .switchIfEmpty(Mono.error(new InventoryDomainException(InventoryErrorCode.INVENTORY_NOT_FOUND,
                        "Inventory not found: " + command.inventoryId())))
                .flatMap(item -> {
                    item.adjust(Quantity.from(command.newQuantity()));
                    return inventoryRepository.save(item)
                            .thenReturn(InventoryResultMapper.toResult(item));
                });
    }
}
