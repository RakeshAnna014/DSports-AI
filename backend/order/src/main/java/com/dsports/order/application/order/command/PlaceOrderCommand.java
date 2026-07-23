package com.dsports.order.application.order.command;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record PlaceOrderCommand(
    @NotNull UUID userId,
    @NotNull UUID checkoutId
) {}
