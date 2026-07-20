package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.ArchiveProductCommand;
import com.dsports.catalog.application.port.ProductRepository;
import com.dsports.catalog.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArchiveProductUseCaseTest {

    @Mock
    private ProductRepository productRepository;

    private ArchiveProductUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ArchiveProductUseCase(productRepository);
    }

    @Test
    void shouldArchiveProduct() {
        var id = ProductId.generate();
        var product = Product.create(
                SKU.from("BAT-001"), ProductName.from("Bat"), Slug.from("bat"),
                null, BrandId.generate(), CategoryId.generate(), SportId.generate(), null, null
        );
        product = Product.reconstitute(id, product.getSku(), product.getName(), product.getSlug(),
                product.getDescription(), product.getBrandId(), product.getCategoryId(), product.getSportId(),
                product.getWeight(), product.getDimensions(), Status.ACTIVE,
                product.getCreatedAt(), product.getUpdatedAt(), 0, product.getImages());

        when(productRepository.findById(id)).thenReturn(Mono.just(product));
        when(productRepository.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(new ArchiveProductCommand(id)))
                .assertNext(result -> org.assertj.core.api.Assertions.assertThat(result.status()).isEqualTo("ARCHIVED"))
                .verifyComplete();

        verify(productRepository).save(any());
    }

    @Test
    void shouldRejectWhenProductNotFound() {
        var id = ProductId.generate();

        when(productRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(new ArchiveProductCommand(id)))
                .expectError(com.dsports.catalog.domain.exception.CatalogDomainException.class)
                .verify();
    }
}
