package com.dsports.catalog.interfaces;

import com.dsports.catalog.application.command.*;
import com.dsports.catalog.application.result.BrandResult;
import com.dsports.catalog.application.result.CategoryResult;
import com.dsports.catalog.application.result.ProductResult;
import com.dsports.catalog.application.result.SportResult;
import com.dsports.catalog.application.usecase.*;
import com.dsports.catalog.domain.model.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Admin")
@SecurityRequirement(name = "bearer-jwt")
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

    private final GetAllSportsUseCase getAllSportsUseCase;
    private final GetAllCategoriesUseCase getAllCategoriesUseCase;
    private final GetAllBrandsUseCase getAllBrandsUseCase;
    private final GetAllProductsUseCase getAllProductsUseCase;

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
                                   GetAllSportsUseCase getAllSportsUseCase,
                                   GetAllCategoriesUseCase getAllCategoriesUseCase,
                                   GetAllBrandsUseCase getAllBrandsUseCase,
                                   GetAllProductsUseCase getAllProductsUseCase,
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
        this.getAllSportsUseCase = getAllSportsUseCase;
        this.getAllCategoriesUseCase = getAllCategoriesUseCase;
        this.getAllBrandsUseCase = getAllBrandsUseCase;
        this.getAllProductsUseCase = getAllProductsUseCase;
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
    @Operation(summary = "Create sport", description = "Create a new sport (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Sport created",
            content = @Content(schema = @Schema(implementation = SportResult.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "409", description = "Sport already exists")
    })
    public Mono<SportResult> createSport(@Valid @RequestBody CreateSportCommand command) {
        return createSportUseCase.execute(command);
    }

    @PutMapping("/sports/{id}")
    @Operation(summary = "Update sport", description = "Update an existing sport (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Sport updated",
            content = @Content(schema = @Schema(implementation = SportResult.class))),
        @ApiResponse(responseCode = "404", description = "Sport not found")
    })
    public Mono<SportResult> updateSport(@Parameter(description = "Sport ID") @PathVariable UUID id,
                                          @Valid @RequestBody UpdateSportRequestBody body) {
        var command = new UpdateSportCommand(SportId.fromUUID(id), body.name(), body.slug(), body.description());
        return updateSportUseCase.execute(command);
    }

    @PostMapping("/sports/{id}/archive")
    @Operation(summary = "Archive sport", description = "Archive a sport (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Sport archived",
            content = @Content(schema = @Schema(implementation = SportResult.class))),
        @ApiResponse(responseCode = "404", description = "Sport not found")
    })
    public Mono<SportResult> archiveSport(@Parameter(description = "Sport ID") @PathVariable UUID id) {
        return archiveSportUseCase.execute(new ArchiveSportCommand(SportId.fromUUID(id)));
    }

    @GetMapping("/sports")
    @Operation(summary = "List all sports", description = "Retrieve all sports including archived (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Sports retrieved")
    })
    public Flux<SportResult> getAllSports() {
        return getAllSportsUseCase.execute();
    }

    @GetMapping("/sports/{id}")
    @Operation(summary = "Get sport by ID", description = "Retrieve a single sport by ID (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Sport found",
            content = @Content(schema = @Schema(implementation = SportResult.class))),
        @ApiResponse(responseCode = "404", description = "Sport not found")
    })
    public Mono<SportResult> getSport(@Parameter(description = "Sport ID") @PathVariable UUID id) {
        return getSportUseCase.execute(SportId.fromUUID(id));
    }

    // ============ CATEGORIES ============

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create category", description = "Create a new category (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Category created",
            content = @Content(schema = @Schema(implementation = CategoryResult.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "409", description = "Category already exists")
    })
    public Mono<CategoryResult> createCategory(@Valid @RequestBody CreateCategoryCommand command) {
        return createCategoryUseCase.execute(command);
    }

    @PutMapping("/categories/{id}")
    @Operation(summary = "Update category", description = "Update an existing category (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Category updated",
            content = @Content(schema = @Schema(implementation = CategoryResult.class))),
        @ApiResponse(responseCode = "404", description = "Category not found")
    })
    public Mono<CategoryResult> updateCategory(@Parameter(description = "Category ID") @PathVariable UUID id,
                                                @Valid @RequestBody UpdateCategoryRequestBody body) {
        var command = new UpdateCategoryCommand(CategoryId.fromUUID(id), body.name(), body.slug(), body.description());
        return updateCategoryUseCase.execute(command);
    }

    @PostMapping("/categories/{id}/archive")
    @Operation(summary = "Archive category", description = "Archive a category (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Category archived",
            content = @Content(schema = @Schema(implementation = CategoryResult.class))),
        @ApiResponse(responseCode = "404", description = "Category not found")
    })
    public Mono<CategoryResult> archiveCategory(@Parameter(description = "Category ID") @PathVariable UUID id) {
        return archiveCategoryUseCase.execute(new ArchiveCategoryCommand(CategoryId.fromUUID(id)));
    }

    @GetMapping("/categories")
    @Operation(summary = "List all categories", description = "Retrieve all categories including archived (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Categories retrieved")
    })
    public Flux<CategoryResult> getAllCategories() {
        return getAllCategoriesUseCase.execute();
    }

    @GetMapping("/categories/{id}")
    @Operation(summary = "Get category by ID", description = "Retrieve a single category by ID (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Category found",
            content = @Content(schema = @Schema(implementation = CategoryResult.class))),
        @ApiResponse(responseCode = "404", description = "Category not found")
    })
    public Mono<CategoryResult> getCategory(@Parameter(description = "Category ID") @PathVariable UUID id) {
        return getCategoryUseCase.execute(CategoryId.fromUUID(id));
    }

    // ============ BRANDS ============

    @PostMapping("/brands")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create brand", description = "Create a new brand (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Brand created",
            content = @Content(schema = @Schema(implementation = BrandResult.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "409", description = "Brand already exists")
    })
    public Mono<BrandResult> createBrand(@Valid @RequestBody CreateBrandCommand command) {
        return createBrandUseCase.execute(command);
    }

    @PutMapping("/brands/{id}")
    @Operation(summary = "Update brand", description = "Update an existing brand (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Brand updated",
            content = @Content(schema = @Schema(implementation = BrandResult.class))),
        @ApiResponse(responseCode = "404", description = "Brand not found")
    })
    public Mono<BrandResult> updateBrand(@Parameter(description = "Brand ID") @PathVariable UUID id,
                                          @Valid @RequestBody UpdateBrandRequestBody body) {
        var command = new UpdateBrandCommand(BrandId.fromUUID(id), body.name(), body.slug(), body.description());
        return updateBrandUseCase.execute(command);
    }

    @PostMapping("/brands/{id}/archive")
    @Operation(summary = "Archive brand", description = "Archive a brand (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Brand archived",
            content = @Content(schema = @Schema(implementation = BrandResult.class))),
        @ApiResponse(responseCode = "404", description = "Brand not found")
    })
    public Mono<BrandResult> archiveBrand(@Parameter(description = "Brand ID") @PathVariable UUID id) {
        return archiveBrandUseCase.execute(new ArchiveBrandCommand(BrandId.fromUUID(id)));
    }

    @GetMapping("/brands")
    @Operation(summary = "List all brands", description = "Retrieve all brands including archived (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Brands retrieved")
    })
    public Flux<BrandResult> getAllBrands() {
        return getAllBrandsUseCase.execute();
    }

    @GetMapping("/brands/{id}")
    @Operation(summary = "Get brand by ID", description = "Retrieve a single brand by ID (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Brand found",
            content = @Content(schema = @Schema(implementation = BrandResult.class))),
        @ApiResponse(responseCode = "404", description = "Brand not found")
    })
    public Mono<BrandResult> getBrand(@Parameter(description = "Brand ID") @PathVariable UUID id) {
        return getBrandUseCase.execute(BrandId.fromUUID(id));
    }

    // ============ PRODUCTS ============

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create product", description = "Create a new product (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Product created",
            content = @Content(schema = @Schema(implementation = ProductResult.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "409", description = "Product already exists")
    })
    public Mono<ProductResult> createProduct(@Valid @RequestBody CreateProductCommand command) {
        return createProductUseCase.execute(command);
    }

    @PutMapping("/products/{id}")
    @Operation(summary = "Update product", description = "Update an existing product (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Product updated",
            content = @Content(schema = @Schema(implementation = ProductResult.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Product not found")
    })
    public Mono<ProductResult> updateProduct(@Parameter(description = "Product ID") @PathVariable UUID id,
                                              @Valid @RequestBody UpdateProductRequestBody body) {
        var command = new UpdateProductCommand(ProductId.fromUUID(id), body.sku(), body.name(), body.slug(),
                body.description(), body.brandId(), body.categoryId(), body.sportId(),
                body.weight(), body.weightUnit(), body.length(), body.width(), body.height(), body.dimensionUnit());
        return updateProductUseCase.execute(command);
    }

    @PatchMapping("/products/{id}/archive")
    @Operation(summary = "Archive product", description = "Archive a product (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Product archived",
            content = @Content(schema = @Schema(implementation = ProductResult.class))),
        @ApiResponse(responseCode = "404", description = "Product not found")
    })
    public Mono<ProductResult> archiveProduct(@Parameter(description = "Product ID") @PathVariable UUID id) {
        return archiveProductUseCase.execute(new ArchiveProductCommand(ProductId.fromUUID(id)));
    }

    @GetMapping("/products")
    @Operation(summary = "List all products", description = "Retrieve all products including archived (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Products retrieved")
    })
    public Flux<ProductResult> getAllProducts() {
        return getAllProductsUseCase.execute();
    }

    @GetMapping("/products/{id}")
    @Operation(summary = "Get product by ID", description = "Retrieve a single product by ID (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Product found",
            content = @Content(schema = @Schema(implementation = ProductResult.class))),
        @ApiResponse(responseCode = "404", description = "Product not found")
    })
    public Mono<ProductResult> getProduct(@Parameter(description = "Product ID") @PathVariable UUID id) {
        return getProductUseCase.execute(ProductId.fromUUID(id));
    }

    @PostMapping("/products/{id}/images")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add product image", description = "Add an image to a product (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Image added",
            content = @Content(schema = @Schema(implementation = ProductResult.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Product not found")
    })
    public Mono<ProductResult> addProductImage(@Parameter(description = "Product ID") @PathVariable UUID id,
                                                @Valid @RequestBody AddProductImageRequest request) {
        var command = new AddProductImageCommand(ProductId.fromUUID(id), request.url(), request.displayOrder(), request.primary());
        return addImageUseCase.execute(command);
    }

    @DeleteMapping("/products/{id}/images/{imageId}")
    @Operation(summary = "Remove product image", description = "Remove an image from a product (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Image removed",
            content = @Content(schema = @Schema(implementation = ProductResult.class))),
        @ApiResponse(responseCode = "404", description = "Product or image not found")
    })
    public Mono<ProductResult> removeProductImage(@Parameter(description = "Product ID") @PathVariable UUID id,
                                                   @Parameter(description = "Image ID") @PathVariable UUID imageId) {
        var command = new RemoveProductImageCommand(ProductId.fromUUID(id), ProductImageId.fromUUID(imageId));
        return removeImageUseCase.execute(command);
    }

    @PutMapping("/products/{id}/images/{imageId}/primary")
    @Operation(summary = "Set primary image", description = "Set a product image as the primary image (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Primary image updated",
            content = @Content(schema = @Schema(implementation = ProductResult.class))),
        @ApiResponse(responseCode = "404", description = "Product or image not found")
    })
    public Mono<ProductResult> changePrimaryImage(@Parameter(description = "Product ID") @PathVariable UUID id,
                                                   @Parameter(description = "Image ID") @PathVariable UUID imageId) {
        var command = new ChangePrimaryImageCommand(ProductId.fromUUID(id), ProductImageId.fromUUID(imageId));
        return changePrimaryImageUseCase.execute(command);
    }
}
