package com.dsports.pricing.interfaces;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

public record UpdatePriceRequestBody(
    @NotNull BigDecimal mrp,
    @NotNull BigDecimal sellingPrice,
    Instant effectiveFrom,
    Instant effectiveTo
) {}
