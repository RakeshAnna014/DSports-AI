package com.dsports.order.application.order.query;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record GetOrdersQuery(
    @NotNull UUID userId
) {}
