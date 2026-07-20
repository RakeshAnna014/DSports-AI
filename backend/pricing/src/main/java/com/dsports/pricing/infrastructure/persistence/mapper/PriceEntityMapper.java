package com.dsports.pricing.infrastructure.persistence.mapper;

import com.dsports.pricing.domain.model.*;
import com.dsports.pricing.infrastructure.persistence.entity.PriceEntity;

public class PriceEntityMapper {

    public PriceEntity toEntity(Price domain) {
        var entity = new PriceEntity();
        entity.setId(domain.getId().value());
        entity.setProductId(domain.getProductId().value());
        entity.setMrp(domain.getMrp().value());
        entity.setSellingPrice(domain.getSellingPrice().value());
        entity.setCurrency(domain.getCurrency().code());
        entity.setEffectiveFrom(domain.getEffectiveDate().effectiveFrom());
        entity.setEffectiveTo(domain.getEffectiveDate().effectiveTo());
        entity.setStatus(domain.getStatus().name());
        entity.setVersion(domain.getVersion());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    public Price toDomain(PriceEntity entity) {
        return Price.reconstitute(
                PriceId.fromUUID(entity.getId()),
                ProductId.fromUUID(entity.getProductId()),
                Money.from(entity.getMrp()),
                Money.from(entity.getSellingPrice()),
                Currency.from(entity.getCurrency()),
                EffectiveDate.from(entity.getEffectiveFrom(), entity.getEffectiveTo()),
                PriceStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion()
        );
    }
}
