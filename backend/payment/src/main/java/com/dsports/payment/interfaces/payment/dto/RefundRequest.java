package com.dsports.payment.interfaces.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "Refund request. Omit the amount to refund the full remaining amount.")
public record RefundRequest(
    @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2)
    @Schema(description = "Amount to refund") BigDecimal amount,
    @Size(max = 500) @Schema(description = "Why the refund is requested") String reason
) {}
