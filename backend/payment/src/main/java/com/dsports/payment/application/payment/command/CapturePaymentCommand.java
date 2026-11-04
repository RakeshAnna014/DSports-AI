package com.dsports.payment.application.payment.command;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Gateway identifiers are read from the stored payment, never taken from the client. */
public record CapturePaymentCommand(
    @NotNull UUID paymentId,
    @NotNull UUID userId
) {}
