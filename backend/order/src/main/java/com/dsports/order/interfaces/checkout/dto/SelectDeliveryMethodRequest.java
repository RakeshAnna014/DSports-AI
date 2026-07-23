package com.dsports.order.interfaces.checkout.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SelectDeliveryMethodRequest(
    @NotBlank @Pattern(regexp = "STANDARD|EXPRESS|NEXT_DAY") String deliveryMethodCode
) {}
