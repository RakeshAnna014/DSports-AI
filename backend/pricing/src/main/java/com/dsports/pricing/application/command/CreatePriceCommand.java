package com.dsports.pricing.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreatePriceCommand(
    @NotNull UUID productId,
    @NotNull BigDecimal mrp,
    @NotNull BigDecimal sellingPrice,
    @NotBlank String currency,
    Instant effectiveFrom,
    Instant effectiveTo
) {}
