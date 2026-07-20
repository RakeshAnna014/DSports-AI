package com.dsports.catalog.domain.event;

import com.dsports.catalog.domain.model.ProductId;
import com.dsports.shared.domain.kernel.DomainEvent;

public final class ProductArchivedEvent extends DomainEvent {

    private final ProductId productId;

    public ProductArchivedEvent(ProductId productId) {
        this.productId = productId;
    }

    public ProductId productId() { return productId; }
}
