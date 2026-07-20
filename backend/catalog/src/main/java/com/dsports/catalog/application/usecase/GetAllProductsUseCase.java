package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.port.ProductRepository;
import com.dsports.catalog.application.result.ProductResult;
import com.dsports.catalog.application.result.ProductResultMapper;
import reactor.core.publisher.Flux;

public class GetAllProductsUseCase {

    private final ProductRepository productRepository;

    public GetAllProductsUseCase(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Flux<ProductResult> execute() {
        return productRepository.findAll()
                .map(ProductResultMapper::toResult);
    }
}
