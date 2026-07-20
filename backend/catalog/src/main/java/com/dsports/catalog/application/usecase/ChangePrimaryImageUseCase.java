package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.ChangePrimaryImageCommand;
import com.dsports.catalog.application.port.ProductRepository;
import com.dsports.catalog.application.result.ProductResult;
import com.dsports.catalog.application.result.ProductResultMapper;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import reactor.core.publisher.Mono;

public class ChangePrimaryImageUseCase {

    private final ProductRepository productRepository;

    public ChangePrimaryImageUseCase(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Mono<ProductResult> execute(ChangePrimaryImageCommand command) {
        return productRepository.findById(command.productId())
                .switchIfEmpty(Mono.error(new CatalogDomainException(CatalogErrorCode.PRODUCT_NOT_FOUND,
                        "Product not found: " + command.productId())))
                .flatMap(product -> {
                    try {
                        product.changePrimaryImage(command.imageId());
                    } catch (IllegalStateException e) {
                        return Mono.error(new CatalogDomainException(CatalogErrorCode.ARCHIVED_ENTITY, e.getMessage()));
                    } catch (IllegalArgumentException e) {
                        return Mono.error(new CatalogDomainException(CatalogErrorCode.GENERIC, e.getMessage()));
                    }
                    return productRepository.save(product)
                            .thenReturn(ProductResultMapper.toResult(product));
                });
    }
}
