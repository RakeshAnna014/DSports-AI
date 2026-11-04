package com.dsports.returns.domain.policy;

import com.dsports.returns.domain.policy.ReturnEligibility.Reason;
import com.dsports.returns.domain.policy.ReturnPolicy.OrderFacts;
import com.dsports.returns.domain.policy.ReturnPolicy.OrderItemFacts;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReturnPolicyTest {

    private static final Instant DELIVERED = Instant.parse("2026-01-10T10:00:00Z");

    private final ReturnPolicy policy = new ReturnPolicy(Duration.ofDays(7));
    private final UUID orderId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    private OrderFacts order(boolean delivered, String status) {
        return new OrderFacts(orderId, delivered, status, DELIVERED, "INR",
            Map.of(productId, new OrderItemFacts("Bat", 3, new BigDecimal("1500.00"))));
    }

    @Test
    void unknownOrderIsNotFound() {
        var result = policy.evaluate(orderId, productId, null, 0, DELIVERED);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(Reason.ORDER_NOT_FOUND);
    }

    @Test
    void undeliveredOrderIsRejected() {
        var result = policy.evaluate(orderId, productId, order(false, "SHIPPED"), 0, DELIVERED);

        assertThat(result.reason()).isEqualTo(Reason.ORDER_NOT_DELIVERED);
        assertThat(result.message()).contains("SHIPPED");
    }

    @Test
    void productOutsideOrderIsRejected() {
        var result = policy.evaluate(orderId, UUID.randomUUID(), order(true, "DELIVERED"), 0, DELIVERED);

        assertThat(result.reason()).isEqualTo(Reason.PRODUCT_NOT_IN_ORDER);
    }

    @Test
    void insideWindowIsEligibleForFullQuantity() {
        var result = policy.evaluate(orderId, productId, order(true, "DELIVERED"), 0, DELIVERED.plus(Duration.ofDays(3)));

        assertThat(result.eligible()).isTrue();
        assertThat(result.maxReturnableQuantity()).isEqualTo(3);
        assertThat(result.returnDeadline()).isEqualTo(DELIVERED.plus(Duration.ofDays(7)));
        assertThat(result.unitPrice()).isEqualByComparingTo("1500.00");
    }

    @Test
    void exactlyAtDeadlineIsStillEligible() {
        var result = policy.evaluate(orderId, productId, order(true, "DELIVERED"), 0, DELIVERED.plus(Duration.ofDays(7)));

        assertThat(result.eligible()).isTrue();
    }

    @Test
    void afterWindowIsRejected() {
        var result = policy.evaluate(orderId, productId, order(true, "DELIVERED"), 0,
            DELIVERED.plus(Duration.ofDays(7)).plusSeconds(1));

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(Reason.RETURN_WINDOW_EXPIRED);
        assertThat(result.maxReturnableQuantity()).isZero();
    }

    @Test
    void partiallyRequestedLeavesRemainder() {
        var result = policy.evaluate(orderId, productId, order(true, "DELIVERED"), 2, DELIVERED.plusSeconds(60));

        assertThat(result.eligible()).isTrue();
        assertThat(result.maxReturnableQuantity()).isEqualTo(1);
        assertThat(result.alreadyRequestedQuantity()).isEqualTo(2);
    }

    @Test
    void fullyRequestedIsRejected() {
        var result = policy.evaluate(orderId, productId, order(true, "DELIVERED"), 3, DELIVERED.plusSeconds(60));

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(Reason.ALREADY_RETURNED);
    }

    @Test
    void windowMustBePositive() {
        assertThatThrownBy(() -> new ReturnPolicy(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReturnPolicy(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
