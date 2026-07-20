package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.CreateProductCommand;
import com.dsports.catalog.application.port.ProductRepository;
import com.dsports.catalog.domain.model.ProductName;
import com.dsports.catalog.domain.model.SKU;
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
class CreateProductUseCaseTest {

    @Mock
    private ProductRepository productRepository;

    private CreateProductUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CreateProductUseCase(productRepository);
    }

    @Test
    void shouldCreateProductSuccessfully() {
        var command = new CreateProductCommand("BAT-001", "Cricket Bat", "cricket-bat", "Desc",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, null, null, null);

        when(productRepository.existsBySku(any(SKU.class))).thenReturn(Mono.just(false));
        when(productRepository.existsBySlug(any())).thenReturn(Mono.just(false));
        when(productRepository.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> {
                    assertThat(result.sku()).isEqualTo("BAT-001");
                    assertThat(result.name()).isEqualTo("Cricket Bat");
                    assertThat(result.slug()).isEqualTo("cricket-bat");
                    assertThat(result.status()).isEqualTo("ACTIVE");
                    assertThat(result.id()).isNotNull();
                })
                .verifyComplete();

        verify(productRepository).save(any());
    }

    @Test
    void shouldRejectDuplicateSku() {
        var command = new CreateProductCommand("BAT-001", "Cricket Bat", "cricket-bat", null,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, null, null, null);

        when(productRepository.existsBySku(any(SKU.class))).thenReturn(Mono.just(true));

        StepVerifier.create(useCase.execute(command))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(com.dsports.catalog.domain.exception.CatalogDomainException.class);
                    assertThat(((com.dsports.catalog.domain.exception.CatalogDomainException) e)
                            .getErrorCode()).isEqualTo(com.dsports.catalog.domain.exception.CatalogErrorCode.DUPLICATE_SKU);
                })
                .verify();

        verify(productRepository, never()).save(any());
    }

    @Test
    void shouldRejectDuplicateSlug() {
        var command = new CreateProductCommand("BAT-001", "Cricket Bat", "cricket-bat", null,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, null, null, null);

        when(productRepository.existsBySku(any(SKU.class))).thenReturn(Mono.just(false));
        when(productRepository.existsBySlug(any())).thenReturn(Mono.just(true));

        StepVerifier.create(useCase.execute(command))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(com.dsports.catalog.domain.exception.CatalogDomainException.class);
                    assertThat(((com.dsports.catalog.domain.exception.CatalogDomainException) e)
                            .getErrorCode()).isEqualTo(com.dsports.catalog.domain.exception.CatalogErrorCode.DUPLICATE_SLUG);
                })
                .verify();

        verify(productRepository, never()).save(any());
    }
}
