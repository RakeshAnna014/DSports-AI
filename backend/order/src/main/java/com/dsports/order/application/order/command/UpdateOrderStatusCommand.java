package com.dsports.order.application.order.command;

import com.dsports.order.domain.order.model.OrderStatus;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record UpdateOrderStatusCommand(
    @NotNull UUID orderId,
    @NotNull OrderStatus newStatus
) {}
