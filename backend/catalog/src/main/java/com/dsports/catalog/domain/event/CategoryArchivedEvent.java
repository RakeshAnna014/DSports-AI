package com.dsports.catalog.domain.event;

import com.dsports.catalog.domain.model.CategoryId;
import com.dsports.shared.domain.kernel.DomainEvent;

public final class CategoryArchivedEvent extends DomainEvent {

    private final CategoryId categoryId;

    public CategoryArchivedEvent(CategoryId categoryId) {
        this.categoryId = categoryId;
    }

    public CategoryId categoryId() { return categoryId; }
}
