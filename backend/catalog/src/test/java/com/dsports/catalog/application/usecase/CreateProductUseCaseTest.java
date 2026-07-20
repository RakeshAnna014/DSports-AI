package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.CreateProductCommand;
import com.dsports.catalog.application.port.BrandRepository;
import com.dsports.catalog.application.port.CategoryRepository;
import com.dsports.catalog.application.port.ProductRepository;
import com.dsports.catalog.application.port.SportRepository;
import com.dsports.catalog.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateProductUseCaseTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private BrandRepository brandRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private SportRepository sportRepository;

    private CreateProductUseCase useCase;

    private static final Brand DEFAULT_BRAND = Brand.create(BrandName.from("Brand"), Slug.from("brand"), null);
    private static final Category DEFAULT_CATEGORY = Category.create(CategoryName.from("Category"), Slug.from("category"), null);
    private static final Sport DEFAULT_SPORT = Sport.create(SportName.from("Sport"), Slug.from("sport"), null);

    @BeforeEach
    void setUp() {
        useCase = new CreateProductUseCase(productRepository, brandRepository, categoryRepository, sportRepository);
    }

    @Test
    void shouldCreateProductSuccessfully() {
        var command = new CreateProductCommand("BAT-001", "Cricket Bat", "cricket-bat", "Desc",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, null, null, null);

        when(brandRepository.findById(any(BrandId.class))).thenReturn(Mono.just(DEFAULT_BRAND));
        when(categoryRepository.findById(any(CategoryId.class))).thenReturn(Mono.just(DEFAULT_CATEGORY));
        when(sportRepository.findById(any(SportId.class))).thenReturn(Mono.just(DEFAULT_SPORT));
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

        when(brandRepository.findById(any(BrandId.class))).thenReturn(Mono.just(DEFAULT_BRAND));
        when(categoryRepository.findById(any(CategoryId.class))).thenReturn(Mono.just(DEFAULT_CATEGORY));
        when(sportRepository.findById(any(SportId.class))).thenReturn(Mono.just(DEFAULT_SPORT));
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

        when(brandRepository.findById(any(BrandId.class))).thenReturn(Mono.just(DEFAULT_BRAND));
        when(categoryRepository.findById(any(CategoryId.class))).thenReturn(Mono.just(DEFAULT_CATEGORY));
        when(sportRepository.findById(any(SportId.class))).thenReturn(Mono.just(DEFAULT_SPORT));
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

    @Test
    void shouldRejectNonExistentBrand() {
        var command = new CreateProductCommand("BAT-001", "Cricket Bat", "cricket-bat", null,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, null, null, null);

        when(brandRepository.findById(any(BrandId.class))).thenReturn(Mono.empty());
        when(categoryRepository.findById(any(CategoryId.class))).thenReturn(Mono.just(DEFAULT_CATEGORY));
        when(sportRepository.findById(any(SportId.class))).thenReturn(Mono.just(DEFAULT_SPORT));
        when(productRepository.existsBySku(any(SKU.class))).thenReturn(Mono.just(false));

        StepVerifier.create(useCase.execute(command))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(com.dsports.catalog.domain.exception.CatalogDomainException.class);
                    assertThat(((com.dsports.catalog.domain.exception.CatalogDomainException) e)
                            .getErrorCode()).isEqualTo(com.dsports.catalog.domain.exception.CatalogErrorCode.BRAND_NOT_FOUND);
                })
                .verify();

        verify(productRepository, never()).save(any());
    }

    @Test
    void shouldRejectNonExistentCategory() {
        var command = new CreateProductCommand("BAT-001", "Cricket Bat", "cricket-bat", null,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, null, null, null);

        when(brandRepository.findById(any(BrandId.class))).thenReturn(Mono.just(DEFAULT_BRAND));
        when(categoryRepository.findById(any(CategoryId.class))).thenReturn(Mono.empty());
        when(sportRepository.findById(any(SportId.class))).thenReturn(Mono.just(DEFAULT_SPORT));
        when(productRepository.existsBySku(any(SKU.class))).thenReturn(Mono.just(false));

        StepVerifier.create(useCase.execute(command))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(com.dsports.catalog.domain.exception.CatalogDomainException.class);
                    assertThat(((com.dsports.catalog.domain.exception.CatalogDomainException) e)
                            .getErrorCode()).isEqualTo(com.dsports.catalog.domain.exception.CatalogErrorCode.CATEGORY_NOT_FOUND);
                })
                .verify();

        verify(productRepository, never()).save(any());
    }

    @Test
    void shouldRejectNonExistentSport() {
        var command = new CreateProductCommand("BAT-001", "Cricket Bat", "cricket-bat", null,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, null, null, null);

        when(brandRepository.findById(any(BrandId.class))).thenReturn(Mono.just(DEFAULT_BRAND));
        when(categoryRepository.findById(any(CategoryId.class))).thenReturn(Mono.just(DEFAULT_CATEGORY));
        when(sportRepository.findById(any(SportId.class))).thenReturn(Mono.empty());
        when(productRepository.existsBySku(any(SKU.class))).thenReturn(Mono.just(false));

        StepVerifier.create(useCase.execute(command))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(com.dsports.catalog.domain.exception.CatalogDomainException.class);
                    assertThat(((com.dsports.catalog.domain.exception.CatalogDomainException) e)
                            .getErrorCode()).isEqualTo(com.dsports.catalog.domain.exception.CatalogErrorCode.SPORT_NOT_FOUND);
                })
                .verify();

        verify(productRepository, never()).save(any());
    }
}
