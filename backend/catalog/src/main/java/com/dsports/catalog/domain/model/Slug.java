package com.dsports.catalog.domain.model;

import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.shared.domain.kernel.ValueObject;

import java.util.Objects;

public record Slug(String value) implements ValueObject {

    public Slug {
        if (value == null || value.isBlank()) {
            throw new CatalogDomainException(CatalogErrorCode.INVALID_SLUG, "Slug must not be blank");
        }
    }

    public static Slug from(String value) {
        return new Slug(value);
    }
}
