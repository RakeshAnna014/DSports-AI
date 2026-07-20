package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.UpdateProductCommand;
import com.dsports.catalog.application.port.ProductRepository;
import com.dsports.catalog.application.result.ProductResult;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.catalog.domain.model.*;
import reactor.core.publisher.Mono;

public class UpdateProductUseCase {

    private final ProductRepository productRepository;

    public UpdateProductUseCase(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Mono<ProductResult> execute(UpdateProductCommand command) {
        return productRepository.findById(command.productId())
                .switchIfEmpty(Mono.error(new CatalogDomainException(CatalogErrorCode.PRODUCT_NOT_FOUND,
                        "Product not found: " + command.productId())))
                .flatMap(product -> {
                    var newSku = SKU.from(command.sku());
                    var newName = ProductName.from(command.name());
                    var newSlug = Slug.from(command.slug());
                    var newDescription = ProductDescription.from(command.description());
                    var newBrandId = BrandId.fromUUID(command.brandId());
                    var newCategoryId = CategoryId.fromUUID(command.categoryId());
                    var newSportId = SportId.fromUUID(command.sportId());
                    var newWeight = command.weight() != null
                            ? Weight.from(command.weight(), command.weightUnit())
                            : null;
                    var newDimensions = command.length() != null || command.width() != null || command.height() != null
                            ? Dimensions.from(command.length(), command.width(), command.height(), command.dimensionUnit())
                            : null;

                    var skuCheck = product.getSku().value().equals(command.sku())
                            ? Mono.just(false)
                            : productRepository.existsBySku(newSku);

                    var slugCheck = product.getSlug().value().equals(command.slug())
                            ? Mono.just(false)
                            : productRepository.existsBySlug(newSlug);

                    return Mono.zip(skuCheck, slugCheck)
                            .flatMap(tuple -> {
                                if (tuple.getT1()) {
                                    return Mono.error(new CatalogDomainException(CatalogErrorCode.DUPLICATE_SKU,
                                            "Product with SKU '" + command.sku() + "' already exists"));
                                }
                                if (tuple.getT2()) {
                                    return Mono.error(new CatalogDomainException(CatalogErrorCode.DUPLICATE_SLUG,
                                            "Product with slug '" + command.slug() + "' already exists"));
                                }
                                try {
                                    product.update(newSku, newName, newSlug, newDescription,
                                            newBrandId, newCategoryId, newSportId, newWeight, newDimensions);
                                } catch (IllegalStateException e) {
                                    return Mono.error(new CatalogDomainException(CatalogErrorCode.ARCHIVED_ENTITY,
                                            "Cannot update an archived product"));
                                }
                                return productRepository.save(product)
                                        .thenReturn(CreateProductUseCase.toResult(product));
                            });
                });
    }
}
