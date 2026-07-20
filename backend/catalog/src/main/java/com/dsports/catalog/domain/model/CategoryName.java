package com.dsports.catalog.domain.model;

import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.shared.domain.kernel.ValueObject;

public record CategoryName(String value) implements ValueObject {

    public CategoryName {
        String trimmed = value != null ? value.trim() : null;
        if (trimmed == null || trimmed.isBlank()) {
            throw new CatalogDomainException(CatalogErrorCode.INVALID_CATEGORY_NAME, "Category name must not be blank");
        }
        if (trimmed.length() > 100) {
            throw new CatalogDomainException(CatalogErrorCode.INVALID_CATEGORY_NAME, "Category name must not exceed 100 characters");
        }
        value = trimmed;
    }

    public static CategoryName from(String value) {
        return new CategoryName(value);
    }
}
