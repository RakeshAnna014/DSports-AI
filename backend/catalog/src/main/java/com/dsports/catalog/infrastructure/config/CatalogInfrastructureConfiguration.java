package com.dsports.catalog.infrastructure.config;

import com.dsports.catalog.application.port.BrandRepository;
import com.dsports.catalog.application.port.CategoryRepository;
import com.dsports.catalog.application.port.EventPublisher;
import com.dsports.catalog.application.port.ProductRepository;
import com.dsports.catalog.application.port.SportRepository;
import com.dsports.catalog.application.usecase.*;
import com.dsports.catalog.infrastructure.event.CatalogSpringEventPublisherAdapter;
import com.dsports.catalog.infrastructure.persistence.mapper.CatalogEntityMapper;
import com.dsports.catalog.infrastructure.persistence.mapper.ProductEntityMapper;
import com.dsports.catalog.infrastructure.persistence.repository.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

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

    // ============ PRODUCT ============

    @Bean
    public ProductEntityMapper productEntityMapper() {
        return new ProductEntityMapper();
    }

    @Bean
    public ProductRepository productRepository(
            DatabaseClient databaseClient,
            ProductEntityMapper mapper,
            SpringR2dbcProductRepository springRepository,
            SpringR2dbcProductImageRepository imageRepository,
            EventPublisher catalogEventPublisher,
            TransactionalOperator transactionalOperator) {
        return new ProductR2dbcRepositoryAdapter(databaseClient, mapper, springRepository,
                imageRepository, catalogEventPublisher, transactionalOperator);
    }

    @Bean
    public TransactionalOperator transactionalOperator(ReactiveTransactionManager reactiveTransactionManager) {
        return TransactionalOperator.create(reactiveTransactionManager);
    }

    @Bean
    public GetAllSportsUseCase getAllSportsUseCase(SportRepository sportRepository) {
        return new GetAllSportsUseCase(sportRepository);
    }

    @Bean
    public GetAllCategoriesUseCase getAllCategoriesUseCase(CategoryRepository categoryRepository) {
        return new GetAllCategoriesUseCase(categoryRepository);
    }

    @Bean
    public GetAllBrandsUseCase getAllBrandsUseCase(BrandRepository brandRepository) {
        return new GetAllBrandsUseCase(brandRepository);
    }

    @Bean
    public GetAllProductsUseCase getAllProductsUseCase(ProductRepository productRepository) {
        return new GetAllProductsUseCase(productRepository);
    }

    @Bean
    public CreateProductUseCase createProductUseCase(ProductRepository productRepository,
                                                      BrandRepository brandRepository,
                                                      CategoryRepository categoryRepository,
                                                      SportRepository sportRepository) {
        return new CreateProductUseCase(productRepository, brandRepository, categoryRepository, sportRepository);
    }

    @Bean
    public UpdateProductUseCase updateProductUseCase(ProductRepository productRepository) {
        return new UpdateProductUseCase(productRepository);
    }

    @Bean
    public ArchiveProductUseCase archiveProductUseCase(ProductRepository productRepository) {
        return new ArchiveProductUseCase(productRepository);
    }

    @Bean
    public GetProductUseCase getProductUseCase(ProductRepository productRepository) {
        return new GetProductUseCase(productRepository);
    }

    @Bean
    public GetProductsUseCase getProductsUseCase(ProductRepository productRepository) {
        return new GetProductsUseCase(productRepository);
    }

    @Bean
    public AddImageUseCase addImageUseCase(ProductRepository productRepository) {
        return new AddImageUseCase(productRepository);
    }

    @Bean
    public RemoveImageUseCase removeImageUseCase(ProductRepository productRepository) {
        return new RemoveImageUseCase(productRepository);
    }

    @Bean
    public ChangePrimaryImageUseCase changePrimaryImageUseCase(ProductRepository productRepository) {
        return new ChangePrimaryImageUseCase(productRepository);
    }
}
