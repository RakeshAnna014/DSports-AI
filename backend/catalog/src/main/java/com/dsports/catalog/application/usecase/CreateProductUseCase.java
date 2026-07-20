package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.CreateProductCommand;
import com.dsports.catalog.application.port.ProductRepository;
import com.dsports.catalog.application.result.ProductImageResult;
import com.dsports.catalog.application.result.ProductResult;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.catalog.domain.model.*;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Collections;

public class CreateProductUseCase {

    private final ProductRepository productRepository;

    public CreateProductUseCase(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Mono<ProductResult> execute(CreateProductCommand command) {
        var sku = SKU.from(command.sku());
        var name = ProductName.from(command.name());
        var slug = Slug.from(command.slug());
        var description = ProductDescription.from(command.description());
        var brandId = BrandId.fromUUID(command.brandId());
        var categoryId = CategoryId.fromUUID(command.categoryId());
        var sportId = SportId.fromUUID(command.sportId());
        var weight = command.weight() != null
                ? Weight.from(command.weight(), command.weightUnit())
                : null;
        var dimensions = command.length() != null || command.width() != null || command.height() != null
                ? Dimensions.from(command.length(), command.width(), command.height(), command.dimensionUnit())
                : null;

        return productRepository.existsBySku(sku)
                .flatMap(skuExists -> {
                    if (skuExists) {
                        return Mono.error(new CatalogDomainException(CatalogErrorCode.DUPLICATE_SKU,
                                "Product with SKU '" + command.sku() + "' already exists"));
                    }
                    return productRepository.existsBySlug(slug);
                })
                .flatMap(slugExists -> {
                    if (slugExists) {
                        return Mono.error(new CatalogDomainException(CatalogErrorCode.DUPLICATE_SLUG,
                                "Product with slug '" + command.slug() + "' already exists"));
                    }
                    var product = Product.create(sku, name, slug, description,
                            brandId, categoryId, sportId, weight, dimensions);
                    return productRepository.save(product)
                            .thenReturn(toResult(product));
                });
    }

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
                        .toList(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
