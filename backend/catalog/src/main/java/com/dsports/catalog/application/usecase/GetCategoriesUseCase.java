package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.port.CategoryRepository;
import com.dsports.catalog.application.result.CategoryResult;
import reactor.core.publisher.Flux;

public class GetCategoriesUseCase {

    private final CategoryRepository categoryRepository;

    public GetCategoriesUseCase(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public Flux<CategoryResult> execute() {
        return categoryRepository.findAllActive()
                .map(CreateCategoryUseCase::toResult);
    }
}
