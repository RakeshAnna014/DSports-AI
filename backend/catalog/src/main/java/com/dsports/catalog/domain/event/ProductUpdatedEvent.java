package com.dsports.catalog.domain.event;

import com.dsports.catalog.domain.model.ProductId;
import com.dsports.catalog.domain.model.ProductName;
import com.dsports.catalog.domain.model.SKU;
import com.dsports.shared.domain.kernel.DomainEvent;

public final class ProductUpdatedEvent extends DomainEvent {

    private final ProductId productId;
    private final ProductName name;
    private final SKU sku;

    public ProductUpdatedEvent(ProductId productId, ProductName name, SKU sku) {
        this.productId = productId;
        this.name = name;
        this.sku = sku;
    }

    public ProductId productId() { return productId; }
    public ProductName name() { return name; }
    public SKU sku() { return sku; }
}
