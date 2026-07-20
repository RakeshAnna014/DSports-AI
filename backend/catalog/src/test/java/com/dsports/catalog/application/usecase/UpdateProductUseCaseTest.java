package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.UpdateProductCommand;
import com.dsports.catalog.application.port.ProductRepository;
import com.dsports.catalog.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UpdateProductUseCaseTest {

    @Mock
    private ProductRepository productRepository;

    private UpdateProductUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new UpdateProductUseCase(productRepository);
    }

    @Test
    void shouldUpdateProductSuccessfully() {
        var id = ProductId.generate();
        var brandId = BrandId.generate();
        var categoryId = CategoryId.generate();
        var sportId = SportId.generate();

        var product = Product.create(
                SKU.from("OLD-001"), ProductName.from("Old"), Slug.from("old"),
                ProductDescription.from("Old desc"), brandId, categoryId, sportId, null, null
        );
        product = Product.reconstitute(id, product.getSku(), product.getName(), product.getSlug(),
                product.getDescription(), product.getBrandId(), product.getCategoryId(), product.getSportId(),
                product.getWeight(), product.getDimensions(), product.getStatus(),
                product.getCreatedAt(), product.getUpdatedAt(), 0, product.getImages());

        var command = new UpdateProductCommand(id, "NEW-001", "New", "new", "New desc",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, null, null, null);

        when(productRepository.findById(id)).thenReturn(Mono.just(product));
        when(productRepository.existsBySku(any(SKU.class))).thenReturn(Mono.just(false));
        when(productRepository.existsBySlug(any())).thenReturn(Mono.just(false));
        when(productRepository.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> {
                    assertThat(result.name()).isEqualTo("New");
                    assertThat(result.sku()).isEqualTo("NEW-001");
                })
                .verifyComplete();

        verify(productRepository).save(any());
    }

    @Test
    void shouldRejectWhenProductNotFound() {
        var id = ProductId.generate();
        var command = new UpdateProductCommand(id, "SKU", "Name", "slug", null,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, null, null, null);

        when(productRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command))
                .expectError(com.dsports.catalog.domain.exception.CatalogDomainException.class)
                .verify();

        verify(productRepository, never()).save(any());
    }
}
