package com.dsports.inventory.infrastructure.persistence.mapper;

import com.dsports.inventory.domain.model.*;
import com.dsports.inventory.infrastructure.persistence.entity.InventoryEntity;

public class InventoryEntityMapper {

    public InventoryEntity toEntity(InventoryItem domain) {
        var entity = new InventoryEntity();
        entity.setId(domain.getId().value());
        entity.setProductId(domain.getProductId().value());
        entity.setWarehouseId(domain.getWarehouseId().value());
        entity.setAvailableQuantity(domain.getAvailableQuantity().value());
        entity.setReservedQuantity(domain.getReservedQuantity().value());
        entity.setReorderLevel(domain.getReorderLevel().value());
        entity.setStatus(domain.getStatus().name());
        entity.setVersion(domain.getVersion());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    public InventoryItem toDomain(InventoryEntity entity) {
        return InventoryItem.reconstitute(
                InventoryId.fromUUID(entity.getId()),
                ProductId.fromUUID(entity.getProductId()),
                WarehouseId.fromUUID(entity.getWarehouseId()),
                Quantity.from(entity.getAvailableQuantity()),
                ReservedQuantity.from(entity.getReservedQuantity()),
                ReorderLevel.from(entity.getReorderLevel()),
                InventoryStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion()
        );
    }
}
