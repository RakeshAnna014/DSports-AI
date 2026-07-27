package com.dsports.payment.interfaces.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request to capture a payment")
public record CapturePaymentRequest(
    @NotBlank @Schema(description = "Transaction ID from the payment gateway") String transactionId,
    @NotBlank @Schema(description = "Gateway reference from initiated payment") String gatewayReference
) {}
