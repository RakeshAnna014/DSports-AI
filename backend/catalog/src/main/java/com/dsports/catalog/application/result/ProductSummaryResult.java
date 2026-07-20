package com.dsports.catalog.application.result;

import java.math.BigDecimal;
import java.time.Instant;
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
    Instant updatedAt
) {}
