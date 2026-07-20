package com.dsports.pricing.application.command;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ArchivePriceCommand(
    @NotNull UUID priceId
) {}
