package com.dsports.catalog.interfaces;

import com.dsports.catalog.application.result.BrandResult;
import com.dsports.catalog.application.result.CategoryResult;
import com.dsports.catalog.application.result.SportResult;
import com.dsports.catalog.application.usecase.GetBrandsUseCase;
import com.dsports.catalog.application.usecase.GetCategoriesUseCase;
import com.dsports.catalog.application.usecase.GetSportsUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicCatalogControllerTest {

    @Mock
    private GetSportsUseCase getSportsUseCase;

    @Mock
    private GetCategoriesUseCase getCategoriesUseCase;

    @Mock
    private GetBrandsUseCase getBrandsUseCase;

    @InjectMocks
    private PublicCatalogController controller;

    @Test
    void shouldReturnSports() {
        var result = new SportResult(UUID.randomUUID(), "Cricket", "cricket", null, "ACTIVE",
                Instant.now(), Instant.now());
        when(getSportsUseCase.execute()).thenReturn(Flux.just(result));

        StepVerifier.create(controller.getSports())
                .expectNextMatches(r -> r.name().equals("Cricket") && r.slug().equals("cricket"))
                .verifyComplete();
    }

    @Test
    void shouldReturnCategories() {
        var result = new CategoryResult(UUID.randomUUID(), "Bat", "bat", null, "ACTIVE",
                Instant.now(), Instant.now());
        when(getCategoriesUseCase.execute()).thenReturn(Flux.just(result));

        StepVerifier.create(controller.getCategories())
                .expectNextMatches(r -> r.name().equals("Bat"))
                .verifyComplete();
    }

    @Test
    void shouldReturnBrands() {
        var result = new BrandResult(UUID.randomUUID(), "MRF", "mrf", null, "ACTIVE",
                Instant.now(), Instant.now());
        when(getBrandsUseCase.execute()).thenReturn(Flux.just(result));

        StepVerifier.create(controller.getBrands())
                .expectNextMatches(r -> r.name().equals("MRF"))
                .verifyComplete();
    }
}
