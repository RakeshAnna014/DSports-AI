package com.dsports.catalog.interfaces;

import com.dsports.catalog.application.result.BrandResult;
import com.dsports.catalog.application.result.CategoryResult;
import com.dsports.catalog.application.result.SportResult;
import com.dsports.catalog.application.usecase.GetBrandsUseCase;
import com.dsports.catalog.application.usecase.GetCategoriesUseCase;
import com.dsports.catalog.application.usecase.GetSportsUseCase;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping(path = "/api/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
public class PublicCatalogController {

    private final GetSportsUseCase getSportsUseCase;
    private final GetCategoriesUseCase getCategoriesUseCase;
    private final GetBrandsUseCase getBrandsUseCase;

    public PublicCatalogController(GetSportsUseCase getSportsUseCase,
                                    GetCategoriesUseCase getCategoriesUseCase,
                                    GetBrandsUseCase getBrandsUseCase) {
        this.getSportsUseCase = getSportsUseCase;
        this.getCategoriesUseCase = getCategoriesUseCase;
        this.getBrandsUseCase = getBrandsUseCase;
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
}
