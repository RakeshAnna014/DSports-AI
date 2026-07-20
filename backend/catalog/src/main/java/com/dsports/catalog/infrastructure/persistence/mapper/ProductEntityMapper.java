package com.dsports.catalog.infrastructure.persistence.mapper;

import com.dsports.catalog.domain.model.*;
import com.dsports.catalog.infrastructure.persistence.entity.ProductEntity;
import com.dsports.catalog.infrastructure.persistence.entity.ProductImageEntity;

import java.util.List;
import java.util.UUID;

public class ProductEntityMapper {

    public ProductEntity toEntity(Product domain) {
        var entity = new ProductEntity();
        entity.setId(domain.getId().value());
        entity.setSku(domain.getSku().value());
        entity.setName(domain.getName().value());
        entity.setSlug(domain.getSlug().value());
        entity.setDescription(domain.getDescription() != null ? domain.getDescription().value() : null);
        entity.setBrandId(domain.getBrandId().value());
        entity.setCategoryId(domain.getCategoryId().value());
        entity.setSportId(domain.getSportId().value());
        entity.setWeight(domain.getWeight() != null ? domain.getWeight().value() : null);
        entity.setWeightUnit(domain.getWeight() != null ? domain.getWeight().unit() : null);
        entity.setLength(domain.getDimensions() != null ? domain.getDimensions().length() : null);
        entity.setWidth(domain.getDimensions() != null ? domain.getDimensions().width() : null);
        entity.setHeight(domain.getDimensions() != null ? domain.getDimensions().height() : null);
        entity.setDimensionUnit(domain.getDimensions() != null ? domain.getDimensions().unit() : null);
        entity.setStatus(domain.getStatus().name());
        entity.setVersion(domain.getVersion());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    public Product toDomain(ProductEntity entity, List<ProductImage> images) {
        return Product.reconstitute(
                ProductId.fromUUID(entity.getId()),
                SKU.from(entity.getSku()),
                ProductName.from(entity.getName()),
                Slug.from(entity.getSlug()),
                ProductDescription.from(entity.getDescription()),
                BrandId.fromUUID(entity.getBrandId()),
                CategoryId.fromUUID(entity.getCategoryId()),
                SportId.fromUUID(entity.getSportId()),
                entity.getWeight() != null
                        ? Weight.from(entity.getWeight(), entity.getWeightUnit())
                        : null,
                entity.getLength() != null || entity.getWidth() != null || entity.getHeight() != null
                        ? Dimensions.from(entity.getLength(), entity.getWidth(), entity.getHeight(), entity.getDimensionUnit())
                        : null,
                Status.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion(),
                images
        );
    }

    public ProductImageEntity toImageEntity(ProductImage domain, UUID productId) {
        var entity = new ProductImageEntity();
        entity.setId(domain.getId().value());
        entity.setProductId(productId);
        entity.setUrl(domain.getUrl().value());
        entity.setDisplayOrder(domain.getDisplayOrder());
        entity.setPrimary(domain.isPrimary());
        return entity;
    }

    public ProductImage toImageDomain(ProductImageEntity entity) {
        return ProductImage.reconstitute(
                ProductImageId.fromUUID(entity.getId()),
                ProductImageUrl.from(entity.getUrl()),
                entity.getDisplayOrder(),
                entity.isPrimary()
        );
    }
}
