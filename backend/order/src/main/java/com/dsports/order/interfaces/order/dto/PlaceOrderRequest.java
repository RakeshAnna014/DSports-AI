package com.dsports.order.interfaces.order.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record PlaceOrderRequest(
    @NotNull UUID checkoutId
) {}
