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
    String sortDir,
    String q
) {
    /** Search without a text query. */
    public ProductFilter(UUID brandId, UUID categoryId, UUID sportId, String status,
                         int page, int size, String sortBy, String sortDir) {
        this(brandId, categoryId, sportId, status, page, size, sortBy, sortDir, null);
    }

    public ProductFilter {
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 20;
        if (sortBy == null || sortBy.isBlank()) sortBy = "created_at";
        q = (q == null || q.isBlank()) ? null : q.trim();
        if (q != null && q.length() > 100) q = q.substring(0, 100);
        if (sortDir == null || (!sortDir.equalsIgnoreCase("asc") && !sortDir.equalsIgnoreCase("desc"))) sortDir = "desc";
    }
}

