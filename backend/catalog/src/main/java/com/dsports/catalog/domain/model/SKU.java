package com.dsports.catalog.domain.model;

import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.shared.domain.kernel.ValueObject;

public record SKU(String value) implements ValueObject {

    public SKU {
        if (value == null || value.isBlank()) {
            throw new CatalogDomainException(CatalogErrorCode.INVALID_SKU, "SKU must not be blank");
        }
        if (value.length() > 50) {
            throw new CatalogDomainException(CatalogErrorCode.INVALID_SKU, "SKU must not exceed 50 characters");
        }
    }

    public static SKU from(String value) {
        return new SKU(value);
    }
}
