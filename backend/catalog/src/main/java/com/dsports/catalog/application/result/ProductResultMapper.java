package com.dsports.catalog.application.result;

import com.dsports.catalog.domain.model.Product;

import java.util.stream.Collectors;

public final class ProductResultMapper {

    private ProductResultMapper() {}

    public static ProductResult toResult(Product product) {
        return new ProductResult(
                product.getId().value(),
                product.getSku().value(),
                product.getName().value(),
                product.getSlug().value(),
                product.getDescription() != null ? product.getDescription().value() : null,
                product.getBrandId().value(),
                product.getCategoryId().value(),
                product.getSportId().value(),
                product.getWeight() != null ? product.getWeight().value() : null,
                product.getWeight() != null ? product.getWeight().unit() : null,
                product.getDimensions() != null ? product.getDimensions().length() : null,
                product.getDimensions() != null ? product.getDimensions().width() : null,
                product.getDimensions() != null ? product.getDimensions().height() : null,
                product.getDimensions() != null ? product.getDimensions().unit() : null,
                product.getStatus().name(),
                product.getImages().stream()
                        .map(img -> new ProductImageResult(img.getId().value(), img.getUrl().value(),
                                img.getDisplayOrder(), img.isPrimary()))
                        .collect(Collectors.toList()),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
