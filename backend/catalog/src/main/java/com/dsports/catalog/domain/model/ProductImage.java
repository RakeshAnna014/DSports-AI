package com.dsports.catalog.domain.model;

import java.util.Objects;

public final class ProductImage {

    private final ProductImageId id;
    private ProductImageUrl url;
    private int displayOrder;
    private boolean primary;

    public ProductImage(ProductImageId id, ProductImageUrl url, int displayOrder, boolean primary) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.url = Objects.requireNonNull(url, "url must not be null");
        this.displayOrder = displayOrder;
        this.primary = primary;
    }

    public static ProductImage create(ProductImageUrl url, int displayOrder, boolean primary) {
        return new ProductImage(ProductImageId.generate(), url, displayOrder, primary);
    }

    public static ProductImage reconstitute(ProductImageId id, ProductImageUrl url, int displayOrder, boolean primary) {
        return new ProductImage(id, url, displayOrder, primary);
    }

    public void changeUrl(ProductImageUrl url) {
        this.url = Objects.requireNonNull(url, "url must not be null");
    }

    void setPrimary(boolean primary) {
        this.primary = primary;
    }

    public void changeDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    public ProductImageId getId() { return id; }
    public ProductImageUrl getUrl() { return url; }
    public int getDisplayOrder() { return displayOrder; }
    public boolean isPrimary() { return primary; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProductImage that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ProductImage{id=" + id + ", primary=" + primary + ", order=" + displayOrder + "}";
    }
}
