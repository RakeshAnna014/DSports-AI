package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.UpdateCategoryCommand;
import com.dsports.catalog.application.port.CategoryRepository;
import com.dsports.catalog.application.result.CategoryResult;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.catalog.domain.model.CategoryName;
import com.dsports.catalog.domain.model.Slug;
import reactor.core.publisher.Mono;

public class UpdateCategoryUseCase {

    private final CategoryRepository categoryRepository;

    public UpdateCategoryUseCase(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public Mono<CategoryResult> execute(UpdateCategoryCommand command) {
        return categoryRepository.findById(command.categoryId())
                .switchIfEmpty(Mono.error(new CatalogDomainException(CatalogErrorCode.CATEGORY_NOT_FOUND,
                        "Category not found: " + command.categoryId())))
                .flatMap(category -> {
                    var newName = CategoryName.from(command.name());
                    var newSlug = Slug.from(command.slug());

                    var nameCheck = category.getName().value().equals(command.name())
                            ? Mono.just(false)
                            : categoryRepository.existsByName(newName);

                    var slugCheck = category.getSlug().value().equals(command.slug())
                            ? Mono.just(false)
                            : categoryRepository.existsBySlug(newSlug);

                    return Mono.zip(nameCheck, slugCheck)
                            .flatMap(tuple -> {
                                if (tuple.getT1()) {
                                    return Mono.error(new CatalogDomainException(CatalogErrorCode.DUPLICATE_CATEGORY_NAME,
                                            "Category with name '" + command.name() + "' already exists"));
                                }
                                if (tuple.getT2()) {
                                    return Mono.error(new CatalogDomainException(CatalogErrorCode.DUPLICATE_SLUG,
                                            "Category with slug '" + command.slug() + "' already exists"));
                                }
                                try {
                                    category.update(newName, newSlug, command.description());
                                } catch (IllegalStateException e) {
                                    return Mono.error(new CatalogDomainException(CatalogErrorCode.ARCHIVED_ENTITY,
                                            "Cannot update an archived category"));
                                }
                                return categoryRepository.save(category)
                                        .thenReturn(CreateCategoryUseCase.toResult(category));
                            });
                });
    }
}
