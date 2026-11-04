package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.port.ProductFilter;
import com.dsports.catalog.application.port.ProductRepository;
import com.dsports.catalog.application.result.ProductSummaryResult;
import com.dsports.catalog.domain.model.ProductImage;
import reactor.core.publisher.Flux;

import java.util.Comparator;
import java.util.List;

public class GetProductsUseCase {

    private final ProductRepository productRepository;

    public GetProductsUseCase(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Flux<ProductSummaryResult> execute(ProductFilter filter) {
        return productRepository.findAll(filter)
                .map(product -> {
                    List<ProductImage> ordered = product.getImages().stream()
                            .sorted(Comparator.comparingInt(ProductImage::getDisplayOrder)).toList();
                    String primary = ordered.stream().filter(ProductImage::isPrimary).findFirst()
                            .or(() -> ordered.stream().findFirst())
                            .map(i -> i.getUrl().value()).orElse(null);
                    return new ProductSummaryResult(
                        product.getId().value(),
                        product.getSku().value(),
                        product.getName().value(),
                        product.getSlug().value(),
                        product.getBrandId().value(),
                        product.getCategoryId().value(),
                        product.getSportId().value(),
                        product.getStatus().name(),
                        product.getCreatedAt(),
                        product.getUpdatedAt(),
                        primary,
                        ordered.stream().map(i -> i.getUrl().value()).toList());
                });
    }
}
