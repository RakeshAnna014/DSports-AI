package com.dsports.cart.domain.model;

import com.dsports.cart.domain.event.CartClearedEvent;
import com.dsports.cart.domain.event.CartCreatedEvent;
import com.dsports.cart.domain.event.CartItemRemovedEvent;
import com.dsports.cart.domain.event.CartItemUpdatedEvent;
import com.dsports.cart.domain.event.ProductAddedToCartEvent;
import com.dsports.cart.domain.exception.CartDomainException;
import com.dsports.cart.domain.exception.CartErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CartTest {

    private static final UserId USER_ID = UserId.fromUUID(UUID.randomUUID());
    private static final String PRODUCT_ID_1 = UUID.randomUUID().toString();
    private static final String PRODUCT_ID_2 = UUID.randomUUID().toString();
    private static final String PRODUCT_NAME_1 = "Cricket Bat";
    private static final String PRODUCT_NAME_2 = "Tennis Ball";
    private static final Money PRICE_100 = Money.from(new BigDecimal("100.00"));
    private static final Money PRICE_200 = Money.from(new BigDecimal("200.00"));

    private Cart cart;

    @BeforeEach
    void setUp() {
        cart = Cart.create(CartId.generate(), USER_ID);
    }

    @Nested
    @DisplayName("Cart Creation")
    class CartCreation {

        @Test
        @DisplayName("Should create cart with ACTIVE status and zero totals")
        void shouldCreateActiveCart() {
            assertNotNull(cart.getId());
            assertEquals(USER_ID, cart.getUserId());
            assertEquals(CartStatus.ACTIVE, cart.getStatus());
            assertEquals(0, cart.getTotalItems());
            assertEquals(BigDecimal.ZERO.setScale(2), cart.getTotalAmount().value());
            assertTrue(cart.getItems().isEmpty());
        }

        @Test
        @DisplayName("Should emit CartCreatedEvent")
        void shouldEmitCartCreatedEvent() {
            var events = cart.getDomainEvents();
            assertEquals(1, events.size());
            assertInstanceOf(CartCreatedEvent.class, events.getFirst());
            var event = (CartCreatedEvent) events.getFirst();
            assertEquals(cart.getId(), event.getCartId());
            assertEquals(USER_ID, event.getUserId());
        }
    }

    @Nested
    @DisplayName("Adding Items")
    class AddingItems {

        @Test
        @DisplayName("Should add new item to cart")
        void shouldAddNewItem() {
            cart.addItem(CartItemId.generate(), PRODUCT_ID_1, PRODUCT_NAME_1, PRICE_100, Quantity.from(2));

            assertEquals(1, cart.getItems().size());
            assertEquals(2, cart.getTotalItems());
            assertEquals(new BigDecimal("200.00"), cart.getTotalAmount().value());

            var events = cart.getDomainEvents();
            assertEquals(2, events.size());
            assertInstanceOf(ProductAddedToCartEvent.class, events.get(1));
        }

        @Test
        @DisplayName("Should increase quantity when adding same product")
        void shouldIncreaseQuantityForExistingProduct() {
            var itemId = CartItemId.generate();
            cart.addItem(itemId, PRODUCT_ID_1, PRODUCT_NAME_1, PRICE_100, Quantity.from(2));
            cart.clearDomainEvents();

            cart.addItem(CartItemId.generate(), PRODUCT_ID_1, PRODUCT_NAME_1, PRICE_100, Quantity.from(3));

            assertEquals(1, cart.getItems().size());
            assertEquals(5, cart.getTotalItems());
            assertEquals(new BigDecimal("500.00"), cart.getTotalAmount().value());
        }

        @Test
        @DisplayName("Should reject quantity exceeding 99")
        void shouldRejectExcessiveQuantity() {
            assertThrows(CartDomainException.class, () -> Quantity.from(100));
        }

        @Test
        @DisplayName("Should reject quantity of zero")
        void shouldRejectZeroQuantity() {
            assertThrows(CartDomainException.class, () -> Quantity.from(0));
        }

        @Test
        @DisplayName("Should reject more than 50 different products")
        void shouldRejectTooManyItems() {
            for (int i = 0; i < 50; i++) {
                cart.addItem(CartItemId.generate(),
                    UUID.randomUUID().toString(), "Product " + i,
                    PRICE_100, Quantity.from(1));
            }
            cart.clearDomainEvents();

            assertThrows(CartDomainException.class,
                () -> cart.addItem(CartItemId.generate(),
                    UUID.randomUUID().toString(), "Product 51",
                    PRICE_100, Quantity.from(1)));
        }
    }

    @Nested
    @DisplayName("Updating Items")
    class UpdatingItems {

        @Test
        @DisplayName("Should update item quantity")
        void shouldUpdateQuantity() {
            var itemId = CartItemId.generate();
            cart.addItem(itemId, PRODUCT_ID_1, PRODUCT_NAME_1, PRICE_100, Quantity.from(2));
            cart.clearDomainEvents();

            cart.updateItemQuantity(itemId, Quantity.from(5));

            assertEquals(5, cart.getItems().getFirst().getQuantity().value());
            assertEquals(new BigDecimal("500.00"), cart.getTotalAmount().value());
        }

        @Test
        @DisplayName("Should throw when updating non-existent item")
        void shouldThrowForNonExistentItem() {
            assertThrows(CartDomainException.class,
                () -> cart.updateItemQuantity(CartItemId.generate(), Quantity.from(3)));
        }

        @Test
        @DisplayName("Should emit CartItemUpdatedEvent")
        void shouldEmitUpdateEvent() {
            var itemId = CartItemId.generate();
            cart.addItem(itemId, PRODUCT_ID_1, PRODUCT_NAME_1, PRICE_100, Quantity.from(2));
            cart.clearDomainEvents();

            cart.updateItemQuantity(itemId, Quantity.from(5));
            var events = cart.getDomainEvents();
            assertEquals(1, events.size());
            assertInstanceOf(CartItemUpdatedEvent.class, events.getFirst());
        }
    }

    @Nested
    @DisplayName("Removing Items")
    class RemovingItems {

        @Test
        @DisplayName("Should remove item from cart")
        void shouldRemoveItem() {
            var itemId = CartItemId.generate();
            cart.addItem(itemId, PRODUCT_ID_1, PRODUCT_NAME_1, PRICE_100, Quantity.from(2));
            cart.clearDomainEvents();

            cart.removeItem(itemId);

            assertTrue(cart.getItems().isEmpty());
            assertEquals(0, cart.getTotalItems());
            assertEquals(BigDecimal.ZERO.setScale(2), cart.getTotalAmount().value());
        }

        @Test
        @DisplayName("Should throw when removing non-existent item")
        void shouldThrowForNonExistentItem() {
            assertThrows(CartDomainException.class,
                () -> cart.removeItem(CartItemId.generate()));
        }

        @Test
        @DisplayName("Should emit CartItemRemovedEvent")
        void shouldEmitRemoveEvent() {
            var itemId = CartItemId.generate();
            cart.addItem(itemId, PRODUCT_ID_1, PRODUCT_NAME_1, PRICE_100, Quantity.from(2));
            cart.clearDomainEvents();

            cart.removeItem(itemId);
            var events = cart.getDomainEvents();
            assertEquals(1, events.size());
            assertInstanceOf(CartItemRemovedEvent.class, events.getFirst());
        }
    }

    @Nested
    @DisplayName("Clearing Cart")
    class ClearingCart {

        @Test
        @DisplayName("Should clear all items")
        void shouldClearAllItems() {
            cart.addItem(CartItemId.generate(), PRODUCT_ID_1, PRODUCT_NAME_1, PRICE_100, Quantity.from(2));
            cart.addItem(CartItemId.generate(), PRODUCT_ID_2, PRODUCT_NAME_2, PRICE_200, Quantity.from(1));
            cart.clearDomainEvents();

            cart.clear();

            assertTrue(cart.getItems().isEmpty());
            assertEquals(0, cart.getTotalItems());
            assertEquals(BigDecimal.ZERO.setScale(2), cart.getTotalAmount().value());
        }

        @Test
        @DisplayName("Should emit CartClearedEvent")
        void shouldEmitClearEvent() {
            cart.addItem(CartItemId.generate(), PRODUCT_ID_1, PRODUCT_NAME_1, PRICE_100, Quantity.from(2));
            cart.clearDomainEvents();

            cart.clear();
            var events = cart.getDomainEvents();
            assertEquals(1, events.size());
            assertInstanceOf(CartClearedEvent.class, events.getFirst());
        }
    }

    @Nested
    @DisplayName("Cart Status Transitions")
    class StatusTransitions {

        @Test
        @DisplayName("Should checkout cart")
        void shouldCheckout() {
            cart.checkout();
            assertEquals(CartStatus.CHECKED_OUT, cart.getStatus());
        }

        @Test
        @DisplayName("Should throw when modifying checked-out cart")
        void shouldThrowForCheckedOutCart() {
            cart.checkout();
            assertThrows(CartDomainException.class,
                () -> cart.addItem(CartItemId.generate(), PRODUCT_ID_1, PRODUCT_NAME_1, PRICE_100, Quantity.from(1)));
            assertThrows(CartDomainException.class,
                () -> cart.updateItemQuantity(CartItemId.generate(), Quantity.from(1)));
            assertThrows(CartDomainException.class,
                () -> cart.removeItem(CartItemId.generate()));
            assertThrows(CartDomainException.class, cart::clear);
        }

        @Test
        @DisplayName("Should abandon active cart")
        void shouldAbandonActiveCart() {
            cart.abandon();
            assertEquals(CartStatus.ABANDONED, cart.getStatus());
        }

        @Test
        @DisplayName("Should throw when abandoning non-active cart")
        void shouldNotAbandonCheckedOutCart() {
            cart.checkout();
            assertThrows(CartDomainException.class, cart::abandon);
        }
    }

    @Nested
    @DisplayName("Reconstitute")
    class Reconstitute {

        @Test
        @DisplayName("Should restore cart from persistence")
        void shouldRestoreCart() {
            var cartId = CartId.generate();
            var now = java.time.Instant.now();
            var itemId = CartItemId.generate();
            var item = CartItem.reconstitute(itemId, cartId, PRODUCT_ID_1, PRODUCT_NAME_1,
                PRICE_100, Quantity.from(2), Money.from(new BigDecimal("200.00")),
                now, now);

            var restored = Cart.reconstitute(cartId, USER_ID, java.util.List.of(item),
                CartStatus.ACTIVE, 2, new BigDecimal("200.00"), 1, now, now);

            assertEquals(cartId, restored.getId());
            assertEquals(USER_ID, restored.getUserId());
            assertEquals(1, restored.getItems().size());
            assertEquals(2, restored.getTotalItems());
            assertEquals(1, restored.getVersion());
        }
    }
}
