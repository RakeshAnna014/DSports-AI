package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.ArchiveProductCommand;
import com.dsports.catalog.application.port.ProductRepository;
import com.dsports.catalog.application.result.ProductResult;
import com.dsports.catalog.application.result.ProductResultMapper;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import reactor.core.publisher.Mono;

public class ArchiveProductUseCase {

    private final ProductRepository productRepository;

    public ArchiveProductUseCase(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Mono<ProductResult> execute(ArchiveProductCommand command) {
        return productRepository.findById(command.productId())
                .switchIfEmpty(Mono.error(new CatalogDomainException(CatalogErrorCode.PRODUCT_NOT_FOUND,
                        "Product not found: " + command.productId())))
                .flatMap(product -> {
                    try {
                        product.archive();
                    } catch (IllegalStateException e) {
                        return Mono.error(new CatalogDomainException(CatalogErrorCode.ARCHIVED_ENTITY,
                                "Product is already archived"));
                    }
                    return productRepository.save(product)
                            .thenReturn(ProductResultMapper.toResult(product));
                });
    }
}
