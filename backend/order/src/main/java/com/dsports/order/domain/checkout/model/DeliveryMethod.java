package com.dsports.order.domain.checkout.model;

import java.math.BigDecimal;

public record DeliveryMethod(String code, String name, BigDecimal charge, String currency, int estimatedDays) {

    public static final DeliveryMethod STANDARD = new DeliveryMethod("STANDARD", "Standard Delivery",
        BigDecimal.valueOf(5.00), "INR", 5);
    public static final DeliveryMethod EXPRESS = new DeliveryMethod("EXPRESS", "Express Delivery",
        BigDecimal.valueOf(15.00), "INR", 2);
    public static final DeliveryMethod NEXT_DAY = new DeliveryMethod("NEXT_DAY", "Next Day Delivery",
        BigDecimal.valueOf(25.00), "INR", 1);

    public static DeliveryMethod fromCode(String code) {
        return switch (code.toUpperCase()) {
            case "STANDARD" -> STANDARD;
            case "EXPRESS" -> EXPRESS;
            case "NEXT_DAY" -> NEXT_DAY;
            default -> throw new IllegalArgumentException("Unknown delivery method: " + code);
        };
    }
}
