package com.dsports.catalog.interfaces;

import com.dsports.catalog.application.command.*;
import com.dsports.catalog.application.port.BrandRepository;
import com.dsports.catalog.application.port.CategoryRepository;
import com.dsports.catalog.application.port.ProductRepository;
import com.dsports.catalog.application.port.SportRepository;
import com.dsports.catalog.application.result.BrandResult;
import com.dsports.catalog.application.result.CategoryResult;
import com.dsports.catalog.application.result.ProductResult;
import com.dsports.catalog.application.result.SportResult;
import com.dsports.catalog.application.usecase.*;
import com.dsports.catalog.domain.model.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/admin/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('ADMIN')")
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

    private final SportRepository sportRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;

    private final CreateProductUseCase createProductUseCase;
    private final UpdateProductUseCase updateProductUseCase;
    private final ArchiveProductUseCase archiveProductUseCase;
    private final GetProductUseCase getProductUseCase;
    private final AddImageUseCase addImageUseCase;
    private final RemoveImageUseCase removeImageUseCase;
    private final ChangePrimaryImageUseCase changePrimaryImageUseCase;

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
                                   GetBrandUseCase getBrandUseCase,
                                   SportRepository sportRepository,
                                   CategoryRepository categoryRepository,
                                   BrandRepository brandRepository,
                                   ProductRepository productRepository,
                                   CreateProductUseCase createProductUseCase,
                                   UpdateProductUseCase updateProductUseCase,
                                   ArchiveProductUseCase archiveProductUseCase,
                                   GetProductUseCase getProductUseCase,
                                   AddImageUseCase addImageUseCase,
                                   RemoveImageUseCase removeImageUseCase,
                                   ChangePrimaryImageUseCase changePrimaryImageUseCase) {
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
        this.sportRepository = sportRepository;
        this.categoryRepository = categoryRepository;
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
        this.createProductUseCase = createProductUseCase;
        this.updateProductUseCase = updateProductUseCase;
        this.archiveProductUseCase = archiveProductUseCase;
        this.getProductUseCase = getProductUseCase;
        this.addImageUseCase = addImageUseCase;
        this.removeImageUseCase = removeImageUseCase;
        this.changePrimaryImageUseCase = changePrimaryImageUseCase;
    }

    // ============ SPORTS ============

    @PostMapping("/sports")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<SportResult> createSport(@Valid @RequestBody CreateSportCommand command) {
        return createSportUseCase.execute(command);
    }

    @PutMapping("/sports/{id}")
    public Mono<SportResult> updateSport(@PathVariable UUID id, @Valid @RequestBody UpdateSportRequestBody body) {
        var command = new UpdateSportCommand(SportId.fromUUID(id), body.name(), body.slug(), body.description());
        return updateSportUseCase.execute(command);
    }

    @PostMapping("/sports/{id}/archive")
    public Mono<SportResult> archiveSport(@PathVariable UUID id) {
        return archiveSportUseCase.execute(new ArchiveSportCommand(SportId.fromUUID(id)));
    }

    @GetMapping("/sports")
    public Flux<SportResult> getAllSports() {
        return sportRepository.findAll().map(CreateSportUseCase::toResult);
    }

    @GetMapping("/sports/{id}")
    public Mono<SportResult> getSport(@PathVariable UUID id) {
        return getSportUseCase.execute(SportId.fromUUID(id));
    }

    // ============ CATEGORIES ============

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<CategoryResult> createCategory(@Valid @RequestBody CreateCategoryCommand command) {
        return createCategoryUseCase.execute(command);
    }

    @PutMapping("/categories/{id}")
    public Mono<CategoryResult> updateCategory(@PathVariable UUID id, @Valid @RequestBody UpdateCategoryRequestBody body) {
        var command = new UpdateCategoryCommand(CategoryId.fromUUID(id), body.name(), body.slug(), body.description());
        return updateCategoryUseCase.execute(command);
    }

    @PostMapping("/categories/{id}/archive")
    public Mono<CategoryResult> archiveCategory(@PathVariable UUID id) {
        return archiveCategoryUseCase.execute(new ArchiveCategoryCommand(CategoryId.fromUUID(id)));
    }

    @GetMapping("/categories")
    public Flux<CategoryResult> getAllCategories() {
        return categoryRepository.findAll().map(CreateCategoryUseCase::toResult);
    }

    @GetMapping("/categories/{id}")
    public Mono<CategoryResult> getCategory(@PathVariable UUID id) {
        return getCategoryUseCase.execute(CategoryId.fromUUID(id));
    }

    // ============ BRANDS ============

    @PostMapping("/brands")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<BrandResult> createBrand(@Valid @RequestBody CreateBrandCommand command) {
        return createBrandUseCase.execute(command);
    }

    @PutMapping("/brands/{id}")
    public Mono<BrandResult> updateBrand(@PathVariable UUID id, @Valid @RequestBody UpdateBrandRequestBody body) {
        var command = new UpdateBrandCommand(BrandId.fromUUID(id), body.name(), body.slug(), body.description());
        return updateBrandUseCase.execute(command);
    }

    @PostMapping("/brands/{id}/archive")
    public Mono<BrandResult> archiveBrand(@PathVariable UUID id) {
        return archiveBrandUseCase.execute(new ArchiveBrandCommand(BrandId.fromUUID(id)));
    }

    @GetMapping("/brands")
    public Flux<BrandResult> getAllBrands() {
        return brandRepository.findAll().map(CreateBrandUseCase::toResult);
    }

    @GetMapping("/brands/{id}")
    public Mono<BrandResult> getBrand(@PathVariable UUID id) {
        return getBrandUseCase.execute(BrandId.fromUUID(id));
    }

    // ============ PRODUCTS ============

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ProductResult> createProduct(@Valid @RequestBody CreateProductCommand command) {
        return createProductUseCase.execute(command);
    }

    @PutMapping("/products/{id}")
    public Mono<ProductResult> updateProduct(@PathVariable UUID id, @Valid @RequestBody UpdateProductRequestBody body) {
        var command = new UpdateProductCommand(ProductId.fromUUID(id), body.sku(), body.name(), body.slug(),
                body.description(), body.brandId(), body.categoryId(), body.sportId(),
                body.weight(), body.weightUnit(), body.length(), body.width(), body.height(), body.dimensionUnit());
        return updateProductUseCase.execute(command);
    }

    @PatchMapping("/products/{id}/archive")
    public Mono<ProductResult> archiveProduct(@PathVariable UUID id) {
        return archiveProductUseCase.execute(new ArchiveProductCommand(ProductId.fromUUID(id)));
    }

    @GetMapping("/products")
    public Flux<ProductResult> getAllProducts() {
        return productRepository.findAll().map(CreateProductUseCase::toResult);
    }

    @GetMapping("/products/{id}")
    public Mono<ProductResult> getProduct(@PathVariable UUID id) {
        return getProductUseCase.execute(ProductId.fromUUID(id));
    }

    @PostMapping("/products/{id}/images")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ProductResult> addProductImage(@PathVariable UUID id, @Valid @RequestBody AddProductImageRequest request) {
        var command = new AddProductImageCommand(ProductId.fromUUID(id), request.url(), request.displayOrder(), request.primary());
        return addImageUseCase.execute(command);
    }

    @DeleteMapping("/products/{id}/images/{imageId}")
    public Mono<ProductResult> removeProductImage(@PathVariable UUID id, @PathVariable UUID imageId) {
        var command = new RemoveProductImageCommand(ProductId.fromUUID(id), ProductImageId.fromUUID(imageId));
        return removeImageUseCase.execute(command);
    }

    @PutMapping("/products/{id}/images/{imageId}/primary")
    public Mono<ProductResult> changePrimaryImage(@PathVariable UUID id, @PathVariable UUID imageId) {
        var command = new ChangePrimaryImageCommand(ProductId.fromUUID(id), ProductImageId.fromUUID(imageId));
        return changePrimaryImageUseCase.execute(command);
    }
}
