package com.dsports.catalog.application.port;

import com.dsports.catalog.domain.model.Category;
import com.dsports.catalog.domain.model.CategoryId;
import com.dsports.catalog.domain.model.CategoryName;
import com.dsports.catalog.domain.model.Slug;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CategoryRepository {
    Mono<Category> findById(CategoryId id);
    Mono<Category> findByName(CategoryName name);
    Mono<Boolean> existsByName(CategoryName name);
    Mono<Boolean> existsBySlug(Slug slug);
    Flux<Category> findAllActive();
    Mono<Void> save(Category category);
}
