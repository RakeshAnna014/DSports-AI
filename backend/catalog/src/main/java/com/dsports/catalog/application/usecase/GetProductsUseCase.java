package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.port.ProductFilter;
import com.dsports.catalog.application.port.ProductRepository;
import com.dsports.catalog.application.result.ProductSummaryResult;
import reactor.core.publisher.Flux;

public class GetProductsUseCase {

    private final ProductRepository productRepository;

    public GetProductsUseCase(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Flux<ProductSummaryResult> execute(ProductFilter filter) {
        return productRepository.findAll(filter)
                .map(product -> new ProductSummaryResult(
                        product.getId().value(),
                        product.getSku().value(),
                        product.getName().value(),
                        product.getSlug().value(),
                        product.getBrandId().value(),
                        product.getCategoryId().value(),
                        product.getSportId().value(),
                        product.getStatus().name(),
                        product.getCreatedAt(),
                        product.getUpdatedAt()
                ));
    }
}
