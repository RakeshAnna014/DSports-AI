package com.dsports.catalog.domain.event;

import com.dsports.catalog.domain.model.CategoryId;
import com.dsports.catalog.domain.model.CategoryName;
import com.dsports.catalog.domain.model.Slug;
import com.dsports.shared.domain.kernel.DomainEvent;

public final class CategoryUpdatedEvent extends DomainEvent {

    private final CategoryId categoryId;
    private final CategoryName name;
    private final Slug slug;

    public CategoryUpdatedEvent(CategoryId categoryId, CategoryName name, Slug slug) {
        this.categoryId = categoryId;
        this.name = name;
        this.slug = slug;
    }

    public CategoryId categoryId() { return categoryId; }
    public CategoryName name() { return name; }
    public Slug slug() { return slug; }
}
