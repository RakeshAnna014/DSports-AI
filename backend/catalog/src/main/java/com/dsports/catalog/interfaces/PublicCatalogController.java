package com.dsports.catalog.interfaces;

import com.dsports.catalog.application.port.ProductFilter;
import com.dsports.catalog.application.result.BrandResult;
import com.dsports.catalog.application.result.CategoryResult;
import com.dsports.catalog.application.result.ProductResult;
import com.dsports.catalog.application.result.ProductSummaryResult;
import com.dsports.catalog.application.result.SportResult;
import com.dsports.catalog.application.usecase.GetBrandsUseCase;
import com.dsports.catalog.application.usecase.GetCategoriesUseCase;
import com.dsports.catalog.application.usecase.GetProductUseCase;
import com.dsports.catalog.application.usecase.GetProductsUseCase;
import com.dsports.catalog.application.usecase.GetSportsUseCase;
import com.dsports.catalog.domain.model.ProductId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Catalog")
public class PublicCatalogController {

    private final GetSportsUseCase getSportsUseCase;
    private final GetCategoriesUseCase getCategoriesUseCase;
    private final GetBrandsUseCase getBrandsUseCase;
    private final GetProductsUseCase getProductsUseCase;
    private final GetProductUseCase getProductUseCase;

    public PublicCatalogController(GetSportsUseCase getSportsUseCase,
                                    GetCategoriesUseCase getCategoriesUseCase,
                                    GetBrandsUseCase getBrandsUseCase,
                                    GetProductsUseCase getProductsUseCase,
                                    GetProductUseCase getProductUseCase) {
        this.getSportsUseCase = getSportsUseCase;
        this.getCategoriesUseCase = getCategoriesUseCase;
        this.getBrandsUseCase = getBrandsUseCase;
        this.getProductsUseCase = getProductsUseCase;
        this.getProductUseCase = getProductUseCase;
    }

    @GetMapping("/sports")
    @Operation(summary = "List sports", description = "Retrieve all available sports")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Sports retrieved successfully")
    })
    public Flux<SportResult> getSports() {
        return getSportsUseCase.execute();
    }

    @GetMapping("/categories")
    @Operation(summary = "List categories", description = "Retrieve all product categories")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Categories retrieved successfully")
    })
    public Flux<CategoryResult> getCategories() {
        return getCategoriesUseCase.execute();
    }

    @GetMapping("/brands")
    @Operation(summary = "List brands", description = "Retrieve all brands")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Brands retrieved successfully")
    })
    public Flux<BrandResult> getBrands() {
        return getBrandsUseCase.execute();
    }

    @GetMapping("/products")
    @Operation(summary = "Search products", description = "Search and filter products with pagination and sorting")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Products retrieved successfully")
    })
    public Flux<ProductSummaryResult> getProducts(
            @Parameter(description = "Filter by brand ID")
            @RequestParam(required = false) UUID brandId,
            @Parameter(description = "Filter by category ID")
            @RequestParam(required = false) UUID categoryId,
            @Parameter(description = "Filter by sport ID")
            @RequestParam(required = false) UUID sportId,
            @Parameter(description = "Page number (zero-based)")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Sort field")
            @RequestParam(defaultValue = "created_at") String sortBy,
            @Parameter(description = "Sort direction (asc/desc)")
            @RequestParam(defaultValue = "desc") String sortDir) {
        var filter = new ProductFilter(brandId, categoryId, sportId, "ACTIVE", page, size, sortBy, sortDir);
        return getProductsUseCase.execute(filter);
    }

    @GetMapping("/products/{id}")
    @Operation(summary = "Get product", description = "Retrieve a single product by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Product found",
            content = @Content(schema = @Schema(implementation = ProductResult.class))),
        @ApiResponse(responseCode = "404", description = "Product not found")
    })
    public Mono<ProductResult> getProduct(@Parameter(description = "Product ID") @PathVariable UUID id) {
        return getProductUseCase.execute(ProductId.fromUUID(id));
    }
}
