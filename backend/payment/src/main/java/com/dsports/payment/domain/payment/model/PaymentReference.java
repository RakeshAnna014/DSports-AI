package com.dsports.payment.domain.payment.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;

public record PaymentReference(String value) {
    public PaymentReference {
        Objects.requireNonNull(value, "PaymentReference must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("PaymentReference must not be blank");
        }
    }

    public static PaymentReference generate() {
        var now = ZonedDateTime.ofInstant(Instant.now(), ZoneId.of("UTC"));
        var timestamp = String.format("%d%02d%02d-%04d",
            now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
            (int) (Instant.now().toEpochMilli() % 10000));
        return new PaymentReference("PAY-" + timestamp + "-" +
            UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    }
}
