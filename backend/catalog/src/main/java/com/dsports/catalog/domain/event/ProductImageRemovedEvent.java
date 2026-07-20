package com.dsports.catalog.domain.event;

import com.dsports.catalog.domain.model.ProductId;
import com.dsports.catalog.domain.model.ProductImageId;
import com.dsports.shared.domain.kernel.DomainEvent;

public final class ProductImageRemovedEvent extends DomainEvent {

    private final ProductId productId;
    private final ProductImageId imageId;

    public ProductImageRemovedEvent(ProductId productId, ProductImageId imageId) {
        this.productId = productId;
        this.imageId = imageId;
    }

    public ProductId productId() { return productId; }
    public ProductImageId imageId() { return imageId; }
}
