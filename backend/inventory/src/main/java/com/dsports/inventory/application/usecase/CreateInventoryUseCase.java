package com.dsports.inventory.application.usecase;

import com.dsports.inventory.application.command.CreateInventoryCommand;
import com.dsports.inventory.application.port.InventoryRepository;
import com.dsports.inventory.application.result.InventoryResult;
import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import com.dsports.inventory.domain.model.*;
import reactor.core.publisher.Mono;

public class CreateInventoryUseCase {

    private final InventoryRepository inventoryRepository;

    public CreateInventoryUseCase(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    public Mono<InventoryResult> execute(CreateInventoryCommand command) {
        return Mono.defer(() -> {
            var productId = ProductId.fromUUID(command.productId());
            var warehouseId = WarehouseId.fromUUID(command.warehouseId());
            var quantity = Quantity.from(command.initialQuantity());
            var reorderLevel = ReorderLevel.from(command.reorderLevel());

            return inventoryRepository.existsByProductIdAndWarehouseId(productId, warehouseId)
                    .flatMap(exists -> {
                        if (exists) {
                            return Mono.error(new InventoryDomainException(InventoryErrorCode.DUPLICATE_INVENTORY,
                                    "Inventory already exists for product " + command.productId()
                                            + " in warehouse " + command.warehouseId()));
                        }
                        var item = InventoryItem.create(productId, warehouseId, quantity, reorderLevel);
                        return inventoryRepository.save(item)
                                .thenReturn(InventoryResultMapper.toResult(item));
                    });
        });
    }
}
