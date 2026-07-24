package com.dsports.payment.application.payment.command;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record CreatePaymentCommand(
    @NotNull UUID userId,
    @NotNull UUID orderId,
    @NotNull BigDecimal amount,
    @NotNull String currency,
    @NotNull String paymentMethod,
    @NotNull String paymentProvider
) {}
