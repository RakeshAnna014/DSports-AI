package com.dsports.payment.application.payment.query;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record GetPaymentHistoryQuery(
    @NotNull UUID userId
) {}
