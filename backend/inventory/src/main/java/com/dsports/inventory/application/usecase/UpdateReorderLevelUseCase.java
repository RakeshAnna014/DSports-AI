package com.dsports.inventory.application.usecase;

import com.dsports.inventory.application.command.UpdateReorderLevelCommand;
import com.dsports.inventory.application.port.InventoryRepository;
import com.dsports.inventory.application.result.InventoryResult;
import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import com.dsports.inventory.domain.model.ReorderLevel;
import reactor.core.publisher.Mono;

public class UpdateReorderLevelUseCase {

    private final InventoryRepository inventoryRepository;

    public UpdateReorderLevelUseCase(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    public Mono<InventoryResult> execute(UpdateReorderLevelCommand command) {
        return inventoryRepository.findById(command.inventoryId())
                .switchIfEmpty(Mono.error(new InventoryDomainException(InventoryErrorCode.INVENTORY_NOT_FOUND,
                        "Inventory not found: " + command.inventoryId())))
                .flatMap(item -> {
                    item.changeReorderLevel(ReorderLevel.from(command.reorderLevel()));
                    return inventoryRepository.save(item)
                            .thenReturn(InventoryResultMapper.toResult(item));
                });
    }
}
