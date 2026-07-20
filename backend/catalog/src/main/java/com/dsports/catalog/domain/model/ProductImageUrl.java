package com.dsports.catalog.domain.model;

import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.shared.domain.kernel.ValueObject;

public record ProductImageUrl(String value) implements ValueObject {

    public ProductImageUrl {
        if (value == null || value.isBlank()) {
            throw new CatalogDomainException(CatalogErrorCode.INVALID_IMAGE_URL, "Image URL must not be blank");
        }
        if (value.length() > 2048) {
            throw new CatalogDomainException(CatalogErrorCode.INVALID_IMAGE_URL, "Image URL must not exceed 2048 characters");
        }
    }

    public static ProductImageUrl from(String value) {
        return new ProductImageUrl(value);
    }
}
