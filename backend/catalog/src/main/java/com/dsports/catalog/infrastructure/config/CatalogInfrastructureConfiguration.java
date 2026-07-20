package com.dsports.catalog.infrastructure.config;

import com.dsports.catalog.application.port.BrandRepository;
import com.dsports.catalog.application.port.CategoryRepository;
import com.dsports.catalog.application.port.EventPublisher;
import com.dsports.catalog.application.port.SportRepository;
import com.dsports.catalog.application.usecase.ArchiveBrandUseCase;
import com.dsports.catalog.application.usecase.ArchiveCategoryUseCase;
import com.dsports.catalog.application.usecase.ArchiveSportUseCase;
import com.dsports.catalog.application.usecase.CreateBrandUseCase;
import com.dsports.catalog.application.usecase.CreateCategoryUseCase;
import com.dsports.catalog.application.usecase.CreateSportUseCase;
import com.dsports.catalog.application.usecase.GetBrandsUseCase;
import com.dsports.catalog.application.usecase.GetCategoriesUseCase;
import com.dsports.catalog.application.usecase.GetCategoryUseCase;
import com.dsports.catalog.application.usecase.GetBrandUseCase;
import com.dsports.catalog.application.usecase.GetSportUseCase;
import com.dsports.catalog.application.usecase.GetSportsUseCase;
import com.dsports.catalog.application.usecase.UpdateBrandUseCase;
import com.dsports.catalog.application.usecase.UpdateCategoryUseCase;
import com.dsports.catalog.application.usecase.UpdateSportUseCase;
import com.dsports.catalog.infrastructure.event.CatalogSpringEventPublisherAdapter;
import com.dsports.catalog.infrastructure.persistence.mapper.CatalogEntityMapper;
import com.dsports.catalog.infrastructure.persistence.repository.BrandR2dbcRepositoryAdapter;
import com.dsports.catalog.infrastructure.persistence.repository.CategoryR2dbcRepositoryAdapter;
import com.dsports.catalog.infrastructure.persistence.repository.SportR2dbcRepositoryAdapter;
import com.dsports.catalog.infrastructure.persistence.repository.SpringR2dbcBrandRepository;
import com.dsports.catalog.infrastructure.persistence.repository.SpringR2dbcCategoryRepository;
import com.dsports.catalog.infrastructure.persistence.repository.SpringR2dbcSportRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;

@Configuration
public class CatalogInfrastructureConfiguration {

    @Bean
    public CatalogEntityMapper catalogEntityMapper() {
        return new CatalogEntityMapper();
    }

    @Bean
    public EventPublisher catalogEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        return new CatalogSpringEventPublisherAdapter(applicationEventPublisher);
    }

    // ============ REPOSITORIES ============

    @Bean
    public SportRepository sportRepository(
            DatabaseClient databaseClient,
            CatalogEntityMapper mapper,
            SpringR2dbcSportRepository springRepository,
            EventPublisher catalogEventPublisher) {
        return new SportR2dbcRepositoryAdapter(databaseClient, mapper, springRepository, catalogEventPublisher);
    }

    @Bean
    public CategoryRepository categoryRepository(
            DatabaseClient databaseClient,
            CatalogEntityMapper mapper,
            SpringR2dbcCategoryRepository springRepository,
            EventPublisher catalogEventPublisher) {
        return new CategoryR2dbcRepositoryAdapter(databaseClient, mapper, springRepository, catalogEventPublisher);
    }

    @Bean
    public BrandRepository brandRepository(
            DatabaseClient databaseClient,
            CatalogEntityMapper mapper,
            SpringR2dbcBrandRepository springRepository,
            EventPublisher catalogEventPublisher) {
        return new BrandR2dbcRepositoryAdapter(databaseClient, mapper, springRepository, catalogEventPublisher);
    }

    // ============ SPORT USE CASES ============

    @Bean
    public CreateSportUseCase createSportUseCase(SportRepository sportRepository) {
        return new CreateSportUseCase(sportRepository);
    }

    @Bean
    public UpdateSportUseCase updateSportUseCase(SportRepository sportRepository) {
        return new UpdateSportUseCase(sportRepository);
    }

    @Bean
    public ArchiveSportUseCase archiveSportUseCase(SportRepository sportRepository) {
        return new ArchiveSportUseCase(sportRepository);
    }

    @Bean
    public GetSportUseCase getSportUseCase(SportRepository sportRepository) {
        return new GetSportUseCase(sportRepository);
    }

    @Bean
    public GetSportsUseCase getSportsUseCase(SportRepository sportRepository) {
        return new GetSportsUseCase(sportRepository);
    }

    // ============ CATEGORY USE CASES ============

    @Bean
    public CreateCategoryUseCase createCategoryUseCase(CategoryRepository categoryRepository) {
        return new CreateCategoryUseCase(categoryRepository);
    }

    @Bean
    public UpdateCategoryUseCase updateCategoryUseCase(CategoryRepository categoryRepository) {
        return new UpdateCategoryUseCase(categoryRepository);
    }

    @Bean
    public ArchiveCategoryUseCase archiveCategoryUseCase(CategoryRepository categoryRepository) {
        return new ArchiveCategoryUseCase(categoryRepository);
    }

    @Bean
    public GetCategoryUseCase getCategoryUseCase(CategoryRepository categoryRepository) {
        return new GetCategoryUseCase(categoryRepository);
    }

    @Bean
    public GetCategoriesUseCase getCategoriesUseCase(CategoryRepository categoryRepository) {
        return new GetCategoriesUseCase(categoryRepository);
    }

    // ============ BRAND USE CASES ============

    @Bean
    public CreateBrandUseCase createBrandUseCase(BrandRepository brandRepository) {
        return new CreateBrandUseCase(brandRepository);
    }

    @Bean
    public UpdateBrandUseCase updateBrandUseCase(BrandRepository brandRepository) {
        return new UpdateBrandUseCase(brandRepository);
    }

    @Bean
    public ArchiveBrandUseCase archiveBrandUseCase(BrandRepository brandRepository) {
        return new ArchiveBrandUseCase(brandRepository);
    }

    @Bean
    public GetBrandUseCase getBrandUseCase(BrandRepository brandRepository) {
        return new GetBrandUseCase(brandRepository);
    }

    @Bean
    public GetBrandsUseCase getBrandsUseCase(BrandRepository brandRepository) {
        return new GetBrandsUseCase(brandRepository);
    }
}
