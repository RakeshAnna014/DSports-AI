package com.dsports.catalog.application.result;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProductSummaryResult(
    UUID id,
    String sku,
    String name,
    String slug,
    UUID brandId,
    UUID categoryId,
    UUID sportId,
    String status,
    Instant createdAt,
    Instant updatedAt,
    /** The primary image (or the first one); null when the product has none. */
    String imageUrl,
    /** All image URLs in display order. */
    List<String> images
) {}
