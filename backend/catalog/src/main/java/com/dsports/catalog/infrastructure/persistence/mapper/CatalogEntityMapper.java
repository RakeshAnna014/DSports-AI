package com.dsports.catalog.infrastructure.persistence.mapper;

import com.dsports.catalog.domain.model.Brand;
import com.dsports.catalog.domain.model.BrandId;
import com.dsports.catalog.domain.model.BrandName;
import com.dsports.catalog.domain.model.Category;
import com.dsports.catalog.domain.model.CategoryId;
import com.dsports.catalog.domain.model.CategoryName;
import com.dsports.catalog.domain.model.Slug;
import com.dsports.catalog.domain.model.Sport;
import com.dsports.catalog.domain.model.SportId;
import com.dsports.catalog.domain.model.SportName;
import com.dsports.catalog.domain.model.Status;
import com.dsports.catalog.infrastructure.persistence.entity.BrandEntity;
import com.dsports.catalog.infrastructure.persistence.entity.CategoryEntity;
import com.dsports.catalog.infrastructure.persistence.entity.SportEntity;

public class CatalogEntityMapper {

    // ============ SPORT ============

    public SportEntity toEntity(Sport domain) {
        var entity = new SportEntity();
        entity.setId(domain.getId().value());
        entity.setName(domain.getName().value());
        entity.setSlug(domain.getSlug().value());
        entity.setDescription(domain.getDescription());
        entity.setStatus(domain.getStatus().name());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        entity.setVersion(domain.getVersion());
        return entity;
    }

    public Sport toDomain(SportEntity entity) {
        return Sport.reconstitute(
                SportId.fromUUID(entity.getId()),
                SportName.from(entity.getName()),
                Slug.from(entity.getSlug()),
                entity.getDescription(),
                Status.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion()
        );
    }

    // ============ CATEGORY ============

    public CategoryEntity toEntity(Category domain) {
        var entity = new CategoryEntity();
        entity.setId(domain.getId().value());
        entity.setName(domain.getName().value());
        entity.setSlug(domain.getSlug().value());
        entity.setDescription(domain.getDescription());
        entity.setStatus(domain.getStatus().name());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        entity.setVersion(domain.getVersion());
        return entity;
    }

    public Category toDomain(CategoryEntity entity) {
        return Category.reconstitute(
                CategoryId.fromUUID(entity.getId()),
                CategoryName.from(entity.getName()),
                Slug.from(entity.getSlug()),
                entity.getDescription(),
                Status.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion()
        );
    }

    // ============ BRAND ============

    public BrandEntity toEntity(Brand domain) {
        var entity = new BrandEntity();
        entity.setId(domain.getId().value());
        entity.setName(domain.getName().value());
        entity.setSlug(domain.getSlug().value());
        entity.setDescription(domain.getDescription());
        entity.setStatus(domain.getStatus().name());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        entity.setVersion(domain.getVersion());
        return entity;
    }

    public Brand toDomain(BrandEntity entity) {
        return Brand.reconstitute(
                BrandId.fromUUID(entity.getId()),
                BrandName.from(entity.getName()),
                Slug.from(entity.getSlug()),
                entity.getDescription(),
                Status.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion()
        );
    }
}
