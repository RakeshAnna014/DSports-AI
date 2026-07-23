package com.dsports.order.application.order.query;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record GetOrderQuery(
    @NotNull UUID orderId,
    @NotNull UUID userId
) {}
