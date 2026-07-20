package com.dsports.catalog.domain.event;

import com.dsports.catalog.domain.model.ProductId;
import com.dsports.catalog.domain.model.ProductImageId;
import com.dsports.catalog.domain.model.ProductImageUrl;
import com.dsports.shared.domain.kernel.DomainEvent;

public final class ProductImageAddedEvent extends DomainEvent {

    private final ProductId productId;
    private final ProductImageId imageId;
    private final ProductImageUrl url;

    public ProductImageAddedEvent(ProductId productId, ProductImageId imageId, ProductImageUrl url) {
        this.productId = productId;
        this.imageId = imageId;
        this.url = url;
    }

    public ProductId productId() { return productId; }
    public ProductImageId imageId() { return imageId; }
    public ProductImageUrl url() { return url; }
}
