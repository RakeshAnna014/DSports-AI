package com.dsports.catalog.interfaces;

import com.dsports.catalog.application.command.CreateBrandCommand;
import com.dsports.catalog.application.command.CreateCategoryCommand;
import com.dsports.catalog.application.command.CreateSportCommand;
import com.dsports.catalog.application.port.BrandRepository;
import com.dsports.catalog.application.port.CategoryRepository;
import com.dsports.catalog.application.port.SportRepository;
import com.dsports.catalog.application.result.BrandResult;
import com.dsports.catalog.application.result.CategoryResult;
import com.dsports.catalog.application.result.SportResult;
import com.dsports.catalog.application.usecase.ArchiveBrandUseCase;
import com.dsports.catalog.application.usecase.ArchiveCategoryUseCase;
import com.dsports.catalog.application.usecase.ArchiveSportUseCase;
import com.dsports.catalog.application.usecase.CreateBrandUseCase;
import com.dsports.catalog.application.usecase.CreateCategoryUseCase;
import com.dsports.catalog.application.usecase.CreateSportUseCase;
import com.dsports.catalog.application.usecase.GetBrandUseCase;
import com.dsports.catalog.application.usecase.GetCategoryUseCase;
import com.dsports.catalog.application.usecase.GetSportUseCase;
import com.dsports.catalog.application.usecase.UpdateBrandUseCase;
import com.dsports.catalog.application.usecase.UpdateCategoryUseCase;
import com.dsports.catalog.application.usecase.UpdateSportUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCatalogControllerTest {

    @Mock private CreateSportUseCase createSportUseCase;
    @Mock private UpdateSportUseCase updateSportUseCase;
    @Mock private ArchiveSportUseCase archiveSportUseCase;
    @Mock private GetSportUseCase getSportUseCase;
    @Mock private CreateCategoryUseCase createCategoryUseCase;
    @Mock private UpdateCategoryUseCase updateCategoryUseCase;
    @Mock private ArchiveCategoryUseCase archiveCategoryUseCase;
    @Mock private GetCategoryUseCase getCategoryUseCase;
    @Mock private CreateBrandUseCase createBrandUseCase;
    @Mock private UpdateBrandUseCase updateBrandUseCase;
    @Mock private ArchiveBrandUseCase archiveBrandUseCase;
    @Mock private GetBrandUseCase getBrandUseCase;
    @Mock private SportRepository sportRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private BrandRepository brandRepository;

    @InjectMocks
    private AdminCatalogController controller;

    @Test
    void shouldCreateSport() {
        var result = new SportResult(UUID.randomUUID(), "Cricket", "cricket", null, "ACTIVE",
                Instant.now(), Instant.now());
        when(createSportUseCase.execute(any(CreateSportCommand.class))).thenReturn(Mono.just(result));

        StepVerifier.create(controller.createSport(new CreateSportCommand("Cricket", "cricket", null)))
                .expectNextMatches(r -> r.name().equals("Cricket"))
                .verifyComplete();
    }

    @Test
    void shouldUpdateSport() {
        var id = UUID.randomUUID();
        var result = new SportResult(id, "Updated", "updated", null, "ACTIVE", Instant.now(), Instant.now());
        when(updateSportUseCase.execute(any())).thenReturn(Mono.just(result));

        StepVerifier.create(controller.updateSport(id, new UpdateSportRequestBody("Updated", "updated", null)))
                .expectNextMatches(r -> r.name().equals("Updated"))
                .verifyComplete();
    }

    @Test
    void shouldArchiveSport() {
        var id = UUID.randomUUID();
        var result = new SportResult(id, "Cricket", "cricket", null, "ARCHIVED", Instant.now(), Instant.now());
        when(archiveSportUseCase.execute(any())).thenReturn(Mono.just(result));

        StepVerifier.create(controller.archiveSport(id))
                .expectNextMatches(r -> r.status().equals("ARCHIVED"))
                .verifyComplete();
    }
}
