package com.dsports.order.application.order.command;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CancelOrderCommand(
    @NotNull UUID orderId,
    @NotNull UUID userId
) {}
