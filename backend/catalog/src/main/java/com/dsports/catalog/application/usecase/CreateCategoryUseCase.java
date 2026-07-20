package com.dsports.catalog.application.usecase;

import com.dsports.catalog.application.command.CreateCategoryCommand;
import com.dsports.catalog.application.port.CategoryRepository;
import com.dsports.catalog.application.result.CategoryResult;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.catalog.domain.model.Category;
import com.dsports.catalog.domain.model.CategoryName;
import com.dsports.catalog.domain.model.Slug;
import reactor.core.publisher.Mono;

public class CreateCategoryUseCase {

    private final CategoryRepository categoryRepository;

    public CreateCategoryUseCase(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public Mono<CategoryResult> execute(CreateCategoryCommand command) {
        var name = CategoryName.from(command.name());
        var slug = Slug.from(command.slug());

        return categoryRepository.existsByName(name)
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new CatalogDomainException(CatalogErrorCode.DUPLICATE_CATEGORY_NAME,
                                "Category with name '" + command.name() + "' already exists"));
                    }
                    return categoryRepository.existsBySlug(slug);
                })
                .flatMap(slugExists -> {
                    if (slugExists) {
                        return Mono.error(new CatalogDomainException(CatalogErrorCode.DUPLICATE_SLUG,
                                "Category with slug '" + command.slug() + "' already exists"));
                    }
                    var category = Category.create(name, slug, command.description());
                    return categoryRepository.save(category)
                            .thenReturn(toResult(category));
                });
    }

    public static CategoryResult toResult(Category category) {
        return new CategoryResult(
                category.getId().value(),
                category.getName().value(),
                category.getSlug().value(),
                category.getDescription(),
                category.getStatus().name(),
                category.getCreatedAt(),
                category.getUpdatedAt()
        );
    }
}
