package com.dsports.pricing.application.command;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record UpdatePriceCommand(
    @NotNull UUID priceId,
    @NotNull BigDecimal mrp,
    @NotNull BigDecimal sellingPrice,
    Instant effectiveFrom,
    Instant effectiveTo
) {}
