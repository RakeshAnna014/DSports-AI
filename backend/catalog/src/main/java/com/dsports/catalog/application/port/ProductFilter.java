package com.dsports.catalog.application.port;

import java.util.UUID;

public record ProductFilter(
    UUID brandId,
    UUID categoryId,
    UUID sportId,
    String status,
    int page,
    int size,
    String sortBy,
    String sortDir
) {
    public ProductFilter {
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 20;
        if (sortBy == null || sortBy.isBlank()) sortBy = "created_at";
        if (sortDir == null || (!sortDir.equalsIgnoreCase("asc") && !sortDir.equalsIgnoreCase("desc"))) sortDir = "desc";
    }
}
