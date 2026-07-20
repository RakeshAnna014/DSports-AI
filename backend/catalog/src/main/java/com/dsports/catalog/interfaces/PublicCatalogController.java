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
    public Flux<SportResult> getSports() {
        return getSportsUseCase.execute();
    }

    @GetMapping("/categories")
    public Flux<CategoryResult> getCategories() {
        return getCategoriesUseCase.execute();
    }

    @GetMapping("/brands")
    public Flux<BrandResult> getBrands() {
        return getBrandsUseCase.execute();
    }

    @GetMapping("/products")
    public Flux<ProductSummaryResult> getProducts(
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID sportId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "created_at") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        var filter = new ProductFilter(brandId, categoryId, sportId, "ACTIVE", page, size, sortBy, sortDir);
        return getProductsUseCase.execute(filter);
    }

    @GetMapping("/products/{id}")
    public Mono<ProductResult> getProduct(@PathVariable UUID id) {
        return getProductUseCase.execute(ProductId.fromUUID(id));
    }
}
