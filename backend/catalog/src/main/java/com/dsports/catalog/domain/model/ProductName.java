package com.dsports.catalog.domain.model;

import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.shared.domain.kernel.ValueObject;

public record ProductName(String value) implements ValueObject {

    public ProductName {
        String trimmed = value != null ? value.trim() : null;
        if (trimmed == null || trimmed.isBlank()) {
            throw new CatalogDomainException(CatalogErrorCode.INVALID_PRODUCT_NAME, "Product name must not be blank");
        }
        if (trimmed.length() > 200) {
            throw new CatalogDomainException(CatalogErrorCode.INVALID_PRODUCT_NAME, "Product name must not exceed 200 characters");
        }
        value = trimmed;
    }

    public static ProductName from(String value) {
        return new ProductName(value);
    }
}
