package com.dsports.payment.interfaces.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Request to create a new payment")
public record CreatePaymentRequest(
    @NotNull @Schema(description = "Order ID to pay for") UUID orderId,
    @NotNull @DecimalMin("0.01") @Schema(description = "Payment amount") BigDecimal amount,
    @NotBlank @Schema(description = "Currency code (e.g. INR, USD)") String currency,
    @NotBlank @Schema(description = "Payment method (CARD, UPI, NET_BANKING, WALLET, COD)") String paymentMethod,
    @NotBlank @Schema(description = "Payment provider (MOCK, STRIPE, RAZORPAY, PAYPAL)") String paymentProvider
) {}
