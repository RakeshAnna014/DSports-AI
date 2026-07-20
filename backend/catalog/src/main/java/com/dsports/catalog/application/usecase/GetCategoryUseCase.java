package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.port.CategoryRepository;
import com.dsports.catalog.application.result.CategoryResult;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.catalog.domain.model.CategoryId;
import reactor.core.publisher.Mono;

public class GetCategoryUseCase {

    private final CategoryRepository categoryRepository;

    public GetCategoryUseCase(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public Mono<CategoryResult> execute(CategoryId id) {
        return categoryRepository.findById(id)
                .switchIfEmpty(Mono.error(new CatalogDomainException(CatalogErrorCode.CATEGORY_NOT_FOUND,
                        "Category not found: " + id)))
                .map(CreateCategoryUseCase::toResult);
    }
}
