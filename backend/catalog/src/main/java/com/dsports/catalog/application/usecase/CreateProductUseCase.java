package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.CreateProductCommand;
import com.dsports.catalog.application.port.BrandRepository;
import com.dsports.catalog.application.port.CategoryRepository;
import com.dsports.catalog.application.port.ProductRepository;
import com.dsports.catalog.application.port.SportRepository;
import com.dsports.catalog.application.result.ProductResult;
import com.dsports.catalog.application.result.ProductResultMapper;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.catalog.domain.model.*;
import reactor.core.publisher.Mono;

public class CreateProductUseCase {

    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
    private final CategoryRepository categoryRepository;
    private final SportRepository sportRepository;

    public CreateProductUseCase(ProductRepository productRepository,
                                BrandRepository brandRepository,
                                CategoryRepository categoryRepository,
                                SportRepository sportRepository) {
        this.productRepository = productRepository;
        this.brandRepository = brandRepository;
        this.categoryRepository = categoryRepository;
        this.sportRepository = sportRepository;
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

        return validateReferences(brandId, categoryId, sportId, command)
                .then(productRepository.existsBySku(sku))
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
                            .thenReturn(ProductResultMapper.toResult(product));
                });
    }

    private Mono<Void> validateReferences(BrandId brandId, CategoryId categoryId, SportId sportId,
                                           CreateProductCommand command) {
        return brandRepository.findById(brandId)
                .switchIfEmpty(Mono.error(new CatalogDomainException(CatalogErrorCode.BRAND_NOT_FOUND,
                        "Brand not found: " + command.brandId())))
                .flatMap(ignored -> categoryRepository.findById(categoryId))
                .switchIfEmpty(Mono.error(new CatalogDomainException(CatalogErrorCode.CATEGORY_NOT_FOUND,
                        "Category not found: " + command.categoryId())))
                .flatMap(ignored -> sportRepository.findById(sportId))
                .switchIfEmpty(Mono.error(new CatalogDomainException(CatalogErrorCode.SPORT_NOT_FOUND,
                        "Sport not found: " + command.sportId())))
                .then();
    }
}
