package com.dsports.catalog.interfaces;

import com.dsports.catalog.application.command.ArchiveBrandCommand;
import com.dsports.catalog.application.command.ArchiveCategoryCommand;
import com.dsports.catalog.application.command.ArchiveSportCommand;
import com.dsports.catalog.application.command.CreateBrandCommand;
import com.dsports.catalog.application.command.CreateCategoryCommand;
import com.dsports.catalog.application.command.CreateSportCommand;
import com.dsports.catalog.application.command.UpdateBrandCommand;
import com.dsports.catalog.application.command.UpdateCategoryCommand;
import com.dsports.catalog.application.command.UpdateSportCommand;
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
import com.dsports.catalog.domain.model.BrandId;
import com.dsports.catalog.domain.model.CategoryId;
import com.dsports.catalog.domain.model.SportId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/admin/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
public class AdminCatalogController {

    private final CreateSportUseCase createSportUseCase;
    private final UpdateSportUseCase updateSportUseCase;
    private final ArchiveSportUseCase archiveSportUseCase;
    private final GetSportUseCase getSportUseCase;

    private final CreateCategoryUseCase createCategoryUseCase;
    private final UpdateCategoryUseCase updateCategoryUseCase;
    private final ArchiveCategoryUseCase archiveCategoryUseCase;
    private final GetCategoryUseCase getCategoryUseCase;

    private final CreateBrandUseCase createBrandUseCase;
    private final UpdateBrandUseCase updateBrandUseCase;
    private final ArchiveBrandUseCase archiveBrandUseCase;
    private final GetBrandUseCase getBrandUseCase;

    public AdminCatalogController(CreateSportUseCase createSportUseCase,
                                   UpdateSportUseCase updateSportUseCase,
                                   ArchiveSportUseCase archiveSportUseCase,
                                   GetSportUseCase getSportUseCase,
                                   CreateCategoryUseCase createCategoryUseCase,
                                   UpdateCategoryUseCase updateCategoryUseCase,
                                   ArchiveCategoryUseCase archiveCategoryUseCase,
                                   GetCategoryUseCase getCategoryUseCase,
                                   CreateBrandUseCase createBrandUseCase,
                                   UpdateBrandUseCase updateBrandUseCase,
                                   ArchiveBrandUseCase archiveBrandUseCase,
                                   GetBrandUseCase getBrandUseCase) {
        this.createSportUseCase = createSportUseCase;
        this.updateSportUseCase = updateSportUseCase;
        this.archiveSportUseCase = archiveSportUseCase;
        this.getSportUseCase = getSportUseCase;
        this.createCategoryUseCase = createCategoryUseCase;
        this.updateCategoryUseCase = updateCategoryUseCase;
        this.archiveCategoryUseCase = archiveCategoryUseCase;
        this.getCategoryUseCase = getCategoryUseCase;
        this.createBrandUseCase = createBrandUseCase;
        this.updateBrandUseCase = updateBrandUseCase;
        this.archiveBrandUseCase = archiveBrandUseCase;
        this.getBrandUseCase = getBrandUseCase;
    }

    // ============ SPORTS ============

    @PostMapping("/sports")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<SportResult> createSport(@Valid @RequestBody CreateSportCommand command) {
        return createSportUseCase.execute(command);
    }

    @PutMapping("/sports/{id}")
    public Mono<SportResult> updateSport(@PathVariable UUID id, @Valid @RequestBody CreateSportCommand body) {
        var command = new UpdateSportCommand(SportId.fromUUID(id), body.name(), body.slug(), body.description());
        return updateSportUseCase.execute(command);
    }

    @DeleteMapping("/sports/{id}")
    public Mono<SportResult> archiveSport(@PathVariable UUID id) {
        return archiveSportUseCase.execute(new ArchiveSportCommand(SportId.fromUUID(id)));
    }

    // ============ CATEGORIES ============

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<CategoryResult> createCategory(@Valid @RequestBody CreateCategoryCommand command) {
        return createCategoryUseCase.execute(command);
    }

    @PutMapping("/categories/{id}")
    public Mono<CategoryResult> updateCategory(@PathVariable UUID id, @Valid @RequestBody CreateCategoryCommand body) {
        var command = new UpdateCategoryCommand(CategoryId.fromUUID(id), body.name(), body.slug(), body.description());
        return updateCategoryUseCase.execute(command);
    }

    @DeleteMapping("/categories/{id}")
    public Mono<CategoryResult> archiveCategory(@PathVariable UUID id) {
        return archiveCategoryUseCase.execute(new ArchiveCategoryCommand(CategoryId.fromUUID(id)));
    }

    // ============ BRANDS ============

    @PostMapping("/brands")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<BrandResult> createBrand(@Valid @RequestBody CreateBrandCommand command) {
        return createBrandUseCase.execute(command);
    }

    @PutMapping("/brands/{id}")
    public Mono<BrandResult> updateBrand(@PathVariable UUID id, @Valid @RequestBody CreateBrandCommand body) {
        var command = new UpdateBrandCommand(BrandId.fromUUID(id), body.name(), body.slug(), body.description());
        return updateBrandUseCase.execute(command);
    }

    @DeleteMapping("/brands/{id}")
    public Mono<BrandResult> archiveBrand(@PathVariable UUID id) {
        return archiveBrandUseCase.execute(new ArchiveBrandCommand(BrandId.fromUUID(id)));
    }
}
