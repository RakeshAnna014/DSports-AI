package com.dsports.pricing.application.command;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record SchedulePriceCommand(
    @NotNull UUID priceId,
    @NotNull Instant scheduledFrom
) {}
