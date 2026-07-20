package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.ArchiveCategoryCommand;
import com.dsports.catalog.application.port.CategoryRepository;
import com.dsports.catalog.application.result.CategoryResult;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import reactor.core.publisher.Mono;

public class ArchiveCategoryUseCase {

    private final CategoryRepository categoryRepository;

    public ArchiveCategoryUseCase(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public Mono<CategoryResult> execute(ArchiveCategoryCommand command) {
        return categoryRepository.findById(command.categoryId())
                .switchIfEmpty(Mono.error(new CatalogDomainException(CatalogErrorCode.CATEGORY_NOT_FOUND,
                        "Category not found: " + command.categoryId())))
                .flatMap(category -> {
                    try {
                        category.archive();
                    } catch (IllegalStateException e) {
                        return Mono.error(new CatalogDomainException(CatalogErrorCode.ARCHIVED_ENTITY,
                                "Category is already archived"));
                    }
                    return categoryRepository.save(category)
                            .thenReturn(CreateCategoryUseCase.toResult(category));
                });
    }
}
