package com.dsports.catalog.domain.event;

import com.dsports.catalog.domain.model.ProductId;
import com.dsports.catalog.domain.model.ProductName;
import com.dsports.catalog.domain.model.SKU;
import com.dsports.shared.domain.kernel.DomainEvent;

public final class ProductCreatedEvent extends DomainEvent {

    private final ProductId productId;
    private final SKU sku;
    private final ProductName name;

    public ProductCreatedEvent(ProductId productId, SKU sku, ProductName name) {
        this.productId = productId;
        this.sku = sku;
        this.name = name;
    }

    public ProductId productId() { return productId; }
    public SKU sku() { return sku; }
    public ProductName name() { return name; }
}
