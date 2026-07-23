package com.dsports.order.domain.model;

import com.dsports.order.domain.checkout.event.CheckoutCreatedEvent;
import com.dsports.order.domain.checkout.event.CheckoutDeliveryMethodSelectedEvent;
import com.dsports.order.domain.checkout.event.CheckoutExpiredEvent;
import com.dsports.order.domain.checkout.event.CheckoutReadyForPaymentEvent;
import com.dsports.order.domain.checkout.event.CheckoutShippingAddressSelectedEvent;
import com.dsports.order.domain.checkout.event.CheckoutValidatedEvent;
import com.dsports.order.domain.checkout.exception.CheckoutDomainException;
import com.dsports.order.domain.checkout.exception.CheckoutErrorCode;
import com.dsports.order.domain.checkout.model.Checkout;
import com.dsports.order.domain.checkout.model.CheckoutId;
import com.dsports.order.domain.checkout.model.CheckoutItem;
import com.dsports.order.domain.checkout.model.CheckoutItemId;
import com.dsports.order.domain.checkout.model.CheckoutStatus;
import com.dsports.order.domain.checkout.model.DeliveryMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CheckoutTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID CART_ID = UUID.randomUUID();
    private static final UUID ADDRESS_ID = UUID.randomUUID();
    private static final BigDecimal PRICE_100 = new BigDecimal("100.00");
    private static final BigDecimal PRICE_200 = new BigDecimal("200.00");
    private static final BigDecimal DISCOUNT_0 = BigDecimal.ZERO;

    private Checkout checkout;

    @BeforeEach
    void setUp() {
        var checkoutId = CheckoutId.generate();
        var items = List.of(
            new CheckoutItem(CheckoutItemId.generate(), checkoutId,
                UUID.randomUUID().toString(), "Cricket Bat", "CB-001", 2,
                PRICE_100, PRICE_100.multiply(BigDecimal.valueOf(2)), null, Instant.now()),
            new CheckoutItem(CheckoutItemId.generate(), checkoutId,
                UUID.randomUUID().toString(), "Tennis Ball", "TB-001", 3,
                PRICE_200, PRICE_200.multiply(BigDecimal.valueOf(3)), null, Instant.now())
        );
        checkout = Checkout.create(checkoutId, CUSTOMER_ID, CART_ID, items);
    }

    @Nested
    @DisplayName("Checkout Creation")
    class CheckoutCreation {

        @Test
        @DisplayName("Should create checkout with PENDING status")
        void shouldCreatePendingCheckout() {
            assertNotNull(checkout.getId());
            assertEquals(CUSTOMER_ID, checkout.getCustomerId());
            assertEquals(CART_ID, checkout.getCartId());
            assertEquals(CheckoutStatus.PENDING, checkout.getStatus());
            assertEquals(2, checkout.getItems().size());
            assertEquals(0, new BigDecimal("800.00").compareTo(checkout.getSubtotal()));
            assertEquals(0, new BigDecimal("144.00").compareTo(checkout.getTaxAmount()));
            assertEquals(0, BigDecimal.ZERO.compareTo(checkout.getDeliveryCharge()));
            assertEquals(0, new BigDecimal("944.00").compareTo(checkout.getTotalAmount()));
        }

        @Test
        @DisplayName("Should emit CheckoutCreatedEvent")
        void shouldEmitCreatedEvent() {
            var events = checkout.getDomainEvents();
            assertEquals(1, events.size());
            assertInstanceOf(CheckoutCreatedEvent.class, events.getFirst());
            var event = (CheckoutCreatedEvent) events.getFirst();
            assertEquals(checkout.getId(), event.getCheckoutId());
            assertEquals(CUSTOMER_ID, event.getCustomerId());
        }
    }

    @Nested
    @DisplayName("Shipping Address Selection")
    class ShippingAddressSelection {

        @Test
        @DisplayName("Should select shipping address")
        void shouldSelectAddress() {
            checkout.selectShippingAddress(ADDRESS_ID);
            assertEquals(ADDRESS_ID, checkout.getShippingAddressId());
            var events = checkout.getDomainEvents();
            assertEquals(2, events.size());
            assertInstanceOf(CheckoutShippingAddressSelectedEvent.class, events.get(1));
        }
    }

    @Nested
    @DisplayName("Delivery Method Selection")
    class DeliveryMethodSelection {

        @Test
        @DisplayName("Should select standard delivery")
        void shouldSelectStandardDelivery() {
            checkout.selectDeliveryMethod(DeliveryMethod.STANDARD);
            assertEquals("STANDARD", checkout.getDeliveryMethod().code());
            assertEquals(0, new BigDecimal("5.00").compareTo(checkout.getDeliveryCharge()));
            assertEquals(0, new BigDecimal("949.00").compareTo(checkout.getTotalAmount()));
        }

        @Test
        @DisplayName("Should select express delivery")
        void shouldSelectExpressDelivery() {
            checkout.selectDeliveryMethod(DeliveryMethod.EXPRESS);
            assertEquals("EXPRESS", checkout.getDeliveryMethod().code());
            assertEquals(0, new BigDecimal("15.00").compareTo(checkout.getDeliveryCharge()));
            assertEquals(0, new BigDecimal("959.00").compareTo(checkout.getTotalAmount()));
        }

        @Test
        @DisplayName("Should emit delivery method selected event")
        void shouldEmitEvent() {
            checkout.selectDeliveryMethod(DeliveryMethod.NEXT_DAY);
            var events = checkout.getDomainEvents();
            assertEquals(2, events.size());
            assertInstanceOf(CheckoutDeliveryMethodSelectedEvent.class, events.get(1));
        }
    }

    @Nested
    @DisplayName("Checkout Validation")
    class CheckoutValidation {

        @Test
        @DisplayName("Should validate with address and delivery method")
        void shouldValidate() {
            checkout.selectShippingAddress(ADDRESS_ID);
            checkout.clearDomainEvents();
            checkout.selectDeliveryMethod(DeliveryMethod.STANDARD);
            checkout.clearDomainEvents();

            checkout.validate();
            assertEquals(CheckoutStatus.VALIDATED, checkout.getStatus());
            assertNotNull(checkout.getValidatedAt());
            var events = checkout.getDomainEvents();
            assertEquals(1, events.size());
            assertInstanceOf(CheckoutValidatedEvent.class, events.getFirst());
        }

        @Test
        @DisplayName("Should throw when validating without shipping address")
        void shouldThrowWithoutAddress() {
            checkout.selectDeliveryMethod(DeliveryMethod.STANDARD);
            var ex = assertThrows(CheckoutDomainException.class, checkout::validate);
            assertEquals(CheckoutErrorCode.MISSING_SHIPPING_ADDRESS, ex.getErrorCode());
        }

        @Test
        @DisplayName("Should throw when validating without delivery method")
        void shouldThrowWithoutDelivery() {
            checkout.selectShippingAddress(ADDRESS_ID);
            var ex = assertThrows(CheckoutDomainException.class, checkout::validate);
            assertEquals(CheckoutErrorCode.MISSING_DELIVERY_METHOD, ex.getErrorCode());
        }
    }

    @Nested
    @DisplayName("Ready For Payment")
    class ReadyForPayment {

        @Test
        @DisplayName("Should mark ready for payment after validation")
        void shouldMarkReadyForPayment() {
            checkout.selectShippingAddress(ADDRESS_ID);
            checkout.selectDeliveryMethod(DeliveryMethod.STANDARD);
            checkout.validate();
            checkout.clearDomainEvents();

            checkout.markReadyForPayment();
            assertEquals(CheckoutStatus.READY_FOR_PAYMENT, checkout.getStatus());
            var events = checkout.getDomainEvents();
            assertEquals(1, events.size());
            assertInstanceOf(CheckoutReadyForPaymentEvent.class, events.getFirst());
        }
    }

    @Nested
    @DisplayName("Checkout Expiry and Cancellation")
    class ExpiryAndCancellation {

        @Test
        @DisplayName("Should expire checkout")
        void shouldExpire() {
            checkout.expire();
            assertEquals(CheckoutStatus.EXPIRED, checkout.getStatus());
            var events = checkout.getDomainEvents();
            assertEquals(2, events.size());
            assertInstanceOf(CheckoutExpiredEvent.class, events.get(1));
        }

        @Test
        @DisplayName("Should cancel checkout")
        void shouldCancel() {
            checkout.cancel();
            assertEquals(CheckoutStatus.CANCELLED, checkout.getStatus());
        }

        @Test
        @DisplayName("Should throw when modifying expired checkout")
        void shouldThrowOnExpired() {
            checkout.expire();
            assertThrows(CheckoutDomainException.class,
                () -> checkout.selectShippingAddress(ADDRESS_ID));
            assertThrows(CheckoutDomainException.class,
                () -> checkout.selectDeliveryMethod(DeliveryMethod.STANDARD));
            assertThrows(CheckoutDomainException.class, checkout::validate);
        }

        @Test
        @DisplayName("Should throw when expiring already terminal checkout")
        void shouldThrowOnDoubleExpire() {
            checkout.expire();
            assertThrows(CheckoutDomainException.class, checkout::expire);
        }
    }

    @Nested
    @DisplayName("DeliveryMethod")
    class DeliveryMethodTests {

        @Test
        @DisplayName("Should resolve delivery method from code")
        void shouldResolveFromCode() {
            assertEquals(DeliveryMethod.STANDARD, DeliveryMethod.fromCode("STANDARD"));
            assertEquals(DeliveryMethod.EXPRESS, DeliveryMethod.fromCode("EXPRESS"));
            assertEquals(DeliveryMethod.NEXT_DAY, DeliveryMethod.fromCode("NEXT_DAY"));
        }

        @Test
        @DisplayName("Should throw for unknown code")
        void shouldThrowForUnknownCode() {
            assertThrows(IllegalArgumentException.class,
                () -> DeliveryMethod.fromCode("UNKNOWN"));
        }
    }

    @Nested
    @DisplayName("Checkout Status Transitions")
    class StatusTransitions {

        @Test
        @DisplayName("Should not allow invalid transitions")
        void shouldNotAllowInvalidTransitions() {
            assertThrows(CheckoutDomainException.class, checkout::markReadyForPayment);
        }
    }
}
