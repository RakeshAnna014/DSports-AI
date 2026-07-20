package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.port.ProductRepository;
import com.dsports.catalog.application.result.ProductResult;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.catalog.domain.model.ProductId;
import reactor.core.publisher.Mono;

public class GetProductUseCase {

    private final ProductRepository productRepository;

    public GetProductUseCase(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Mono<ProductResult> execute(ProductId id) {
        return productRepository.findById(id)
                .switchIfEmpty(Mono.error(new CatalogDomainException(CatalogErrorCode.PRODUCT_NOT_FOUND,
                        "Product not found: " + id)))
                .map(CreateProductUseCase::toResult);
    }
}
