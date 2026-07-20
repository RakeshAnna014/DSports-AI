package com.dsports.catalog.domain.model;

import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.shared.domain.kernel.ValueObject;

import java.util.Objects;

public record Slug(String value) implements ValueObject {

    public Slug {
        String normalized = value != null
                ? value.trim().toLowerCase().replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "")
                : null;
        if (normalized == null || normalized.isBlank()) {
            throw new CatalogDomainException(CatalogErrorCode.INVALID_SLUG, "Slug must not be blank");
        }
        value = normalized;
    }

    public static Slug from(String value) {
        return new Slug(value);
    }
}
