package com.dsports.fulfilment;

import com.dsports.fulfilment.FulfilmentBoardController.FulfilmentRow;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FulfilmentBoardTest {

    private static FulfilmentRow row(String orderStatus, String payment, UUID shipmentId, String shipmentStatus) {
        return FulfilmentBoardController.toRow(UUID.randomUUID(), "ORD-1", orderStatus, new BigDecimal("100.00"), "INR", Instant.now(), 2L, "asha.rao@example.com",
            payment, shipmentId, shipmentId == null ? null : "SHP-1", shipmentId == null ? null : "TRK-1", shipmentStatus, null);
    }

    @Test
    void nextStepsAreExactlyWhatTheShipmentStateMachineAllows() {
        assertThat(row("CONFIRMED", "SUCCESS", UUID.randomUUID(), "CREATED").nextShipmentStatuses()).containsExactlyInAnyOrder("READY_FOR_PICKUP", "FAILED");
        assertThat(row("SHIPPED", "SUCCESS", UUID.randomUUID(), "OUT_FOR_DELIVERY").nextShipmentStatuses()).containsExactlyInAnyOrder("DELIVERED", "FAILED");
        assertThat(row("DELIVERED", "SUCCESS", UUID.randomUUID(), "DELIVERED").nextShipmentStatuses()).isEmpty();      // terminal: nothing to offer
        assertThat(row("SHIPPED", "SUCCESS", UUID.randomUUID(), "FAILED").nextShipmentStatuses()).containsExactly("READY_FOR_PICKUP");
    }

    @Test
    void aShipmentCanOnlyBeCreatedForAPaidOrderInFulfilmentThatHasNone() {
        assertThat(row("CONFIRMED", "SUCCESS", null, null).canCreateShipment()).isTrue();
        assertThat(row("PENDING_PAYMENT", "PROCESSING", null, null).canCreateShipment()).isFalse();   // never bypass payment
        assertThat(row("CONFIRMED", "SUCCESS", UUID.randomUUID(), "CREATED").canCreateShipment()).isFalse();
        assertThat(row("CANCELLED", "CANCELLED", null, null).canCreateShipment()).isFalse();
        assertThat(row("CONFIRMED", null, null, null).canCreateShipment()).isFalse();                 // no payment record at all
    }

    @Test
    void paidMeansACapturedPaymentAndNothingElse() {
        assertThat(row("CONFIRMED", "SUCCESS", null, null).paid()).isTrue();
        for (var s : new String[] {"CREATED", "PENDING", "PROCESSING", "AUTHORIZED", "FAILED", "CANCELLED", "REFUNDED"}) {
            assertThat(row("PENDING_PAYMENT", s, null, null).paid()).as(s).isFalse();
        }
    }

    @Test
    void customerEmailsAreMasked() {
        assertThat(row("CONFIRMED", "SUCCESS", null, null).customer()).isEqualTo("a***@example.com").doesNotContain("asha");
        assertThat(FulfilmentBoardController.maskEmail(null)).isNull();
    }

    @Test
    void anUnknownStatusFilterIsRejected() {
        var controller = new FulfilmentBoardController(null);
        assertThatThrownBy(() -> controller.board(java.util.List.of("DROP TABLE"), 10).block()).isInstanceOf(ResponseStatusException.class);
    }
}
