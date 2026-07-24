package com.dsports.payment.application.payment.command;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RefundPaymentCommand(
    @NotNull UUID paymentId,
    @NotNull UUID userId
) {}
