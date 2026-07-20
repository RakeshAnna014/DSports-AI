package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.AddProductImageCommand;
import com.dsports.catalog.application.port.ProductRepository;
import com.dsports.catalog.application.result.ProductResult;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.catalog.domain.model.ProductImageUrl;
import reactor.core.publisher.Mono;

public class AddImageUseCase {

    private final ProductRepository productRepository;

    public AddImageUseCase(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Mono<ProductResult> execute(AddProductImageCommand command) {
        return productRepository.findById(command.productId())
                .switchIfEmpty(Mono.error(new CatalogDomainException(CatalogErrorCode.PRODUCT_NOT_FOUND,
                        "Product not found: " + command.productId())))
                .flatMap(product -> {
                    var url = ProductImageUrl.from(command.url());
                    try {
                        product.addImage(url, command.displayOrder(), command.primary());
                    } catch (IllegalStateException e) {
                        return Mono.error(new CatalogDomainException(CatalogErrorCode.ARCHIVED_ENTITY, e.getMessage()));
                    }
                    return productRepository.save(product)
                            .thenReturn(CreateProductUseCase.toResult(product));
                });
    }
}
