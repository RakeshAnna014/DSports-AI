package com.dsports.catalog.infrastructure.persistence.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("product_images")
public class ProductImageEntity {

    @Id
    private UUID id;

    @Column("product_id")
    private UUID productId;

    private String url;

    @Column("display_order")
    private int displayOrder;

    @Column("is_primary")
    private boolean primary;

    public ProductImageEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getProductId() { return productId; }
    public void setProductId(UUID productId) { this.productId = productId; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }

    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }
}
