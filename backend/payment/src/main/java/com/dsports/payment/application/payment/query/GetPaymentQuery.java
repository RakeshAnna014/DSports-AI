package com.dsports.payment.application.payment.query;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record GetPaymentQuery(
    @NotNull UUID paymentId,
    @NotNull UUID userId
) {}
