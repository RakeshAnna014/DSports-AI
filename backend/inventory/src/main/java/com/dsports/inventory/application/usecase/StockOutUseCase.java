package com.dsports.inventory.application.usecase;

import com.dsports.inventory.application.command.StockOutCommand;
import com.dsports.inventory.application.port.InventoryRepository;
import com.dsports.inventory.application.result.InventoryResult;
import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import com.dsports.inventory.domain.model.Quantity;
import reactor.core.publisher.Mono;

public class StockOutUseCase {

    private final InventoryRepository inventoryRepository;

    public StockOutUseCase(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    public Mono<InventoryResult> execute(StockOutCommand command) {
        return inventoryRepository.findById(command.inventoryId())
                .switchIfEmpty(Mono.error(new InventoryDomainException(InventoryErrorCode.INVENTORY_NOT_FOUND,
                        "Inventory not found: " + command.inventoryId())))
                .flatMap(item -> {
                    item.stockOut(Quantity.from(command.quantity()));
                    return inventoryRepository.save(item)
                            .thenReturn(InventoryResultMapper.toResult(item));
                });
    }
}
