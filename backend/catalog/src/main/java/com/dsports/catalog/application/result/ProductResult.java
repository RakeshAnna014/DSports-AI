package com.dsports.catalog.application.result;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProductResult(
    UUID id,
    String sku,
    String name,
    String slug,
    String description,
    UUID brandId,
    UUID categoryId,
    UUID sportId,
    BigDecimal weight,
    String weightUnit,
    BigDecimal length,
    BigDecimal width,
    BigDecimal height,
    String dimensionUnit,
    String status,
    List<ProductImageResult> images,
    Instant createdAt,
    Instant updatedAt
) {}
