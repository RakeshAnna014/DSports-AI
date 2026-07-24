package com.dsports.payment.application.payment.command;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CapturePaymentCommand(
    @NotNull UUID paymentId,
    @NotNull UUID userId,
    @NotNull String transactionId,
    @NotNull String gatewayReference
) {}
