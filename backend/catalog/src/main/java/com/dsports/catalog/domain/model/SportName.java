package com.dsports.catalog.domain.model;

import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.shared.domain.kernel.ValueObject;

import java.util.Objects;

public record SportName(String value) implements ValueObject {

    public SportName {
        if (value == null || value.isBlank()) {
            throw new CatalogDomainException(CatalogErrorCode.INVALID_SPORT_NAME, "Sport name must not be blank");
        }
        if (value.length() > 100) {
            throw new CatalogDomainException(CatalogErrorCode.INVALID_SPORT_NAME, "Sport name must not exceed 100 characters");
        }
    }

    public static SportName from(String value) {
        return new SportName(value);
    }
}
