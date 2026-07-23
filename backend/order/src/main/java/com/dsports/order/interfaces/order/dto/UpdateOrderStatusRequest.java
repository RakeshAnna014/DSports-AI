package com.dsports.order.interfaces.order.dto;

import com.dsports.order.domain.order.model.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateOrderStatusRequest(
    @NotNull OrderStatus status
) {}
