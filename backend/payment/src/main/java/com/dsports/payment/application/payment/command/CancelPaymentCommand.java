package com.dsports.payment.application.payment.command;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CancelPaymentCommand(
    @NotNull UUID paymentId,
    @NotNull UUID userId
) {}
