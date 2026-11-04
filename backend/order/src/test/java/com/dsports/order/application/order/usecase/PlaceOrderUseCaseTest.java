package com.dsports.order.application.order.usecase;

import com.dsports.order.application.order.command.PlaceOrderCommand;
import com.dsports.order.application.order.port.CartCheckoutPort;
import com.dsports.order.application.order.port.CheckoutDataPort;
import com.dsports.order.application.order.port.EventPublisher;
import com.dsports.order.application.order.port.InventoryReservationPort;
import com.dsports.order.application.order.port.OrderRepository;
import com.dsports.order.domain.order.exception.OrderDomainException;
import com.dsports.order.domain.order.exception.OrderErrorCode;
import com.dsports.shared.application.TransactionRunner;
import com.dsports.shared.application.outbox.OutboxMessage;
import com.dsports.shared.application.outbox.OutboxMessageTypes;
import com.dsports.shared.application.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaceOrderUseCaseTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CheckoutDataPort checkoutDataPort;
    @Mock
    private CartCheckoutPort cartCheckoutPort;
    @Mock
    private InventoryReservationPort inventoryReservationPort;
    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private OutboxWriter outboxWriter;

    private PlaceOrderUseCase useCase;

    private final UUID userId = UUID.randomUUID();
    private final UUID checkoutId = UUID.randomUUID();
    private final UUID cartId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    private static final TransactionRunner PASS_THROUGH = new TransactionRunner() {
        @Override
        public <T> Mono<T> inTransaction(Supplier<Mono<T>> work) {
            return Mono.defer(work);
        }
    };

    @BeforeEach
    void setUp() {
        useCase = new PlaceOrderUseCase(orderRepository, checkoutDataPort, cartCheckoutPort,
            inventoryReservationPort, eventPublisher, PASS_THROUGH, outboxWriter);
        when(orderRepository.existsByCheckoutId(checkoutId)).thenReturn(Mono.just(false));
        when(checkoutDataPort.getValidatedCheckout(checkoutId, userId)).thenReturn(Mono.just(checkoutData()));
        when(orderRepository.nextOrderSequence()).thenReturn(Mono.just(42L));
        when(orderRepository.save(any())).thenReturn(Mono.empty());
        when(inventoryReservationPort.reserve(any(), anyList())).thenReturn(Mono.empty());
        when(cartCheckoutPort.markCartAsCheckedOut(cartId)).thenReturn(Mono.empty());
        when(checkoutDataPort.completeCheckout(checkoutId)).thenReturn(Mono.empty());
        when(outboxWriter.enqueue(any())).thenReturn(Mono.empty());
    }

    @Test
    void placesOrderReservesStockClosesCartCompletesCheckoutAndRecordsNotification() {
        StepVerifier.create(useCase.execute(new PlaceOrderCommand(userId, checkoutId)))
            .assertNext(result -> {
                assertThat(result.status()).isEqualTo("PENDING_PAYMENT");
                assertThat(result.orderNumber()).endsWith("000042");
                assertThat(result.shippingAddress().fullName()).isEqualTo("Test User");
            })
            .verifyComplete();

        var items = ArgumentCaptor.forClass(List.class);
        verify(inventoryReservationPort).reserve(any(), items.capture());
        assertThat(items.getValue()).containsExactly(new InventoryReservationPort.ReservationItem(productId, 2));
        verify(cartCheckoutPort).markCartAsCheckedOut(cartId);
        verify(cartCheckoutPort, never()).markCartAsCheckedOut(checkoutId);
        verify(checkoutDataPort).completeCheckout(checkoutId);

        var message = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxWriter).enqueue(message.capture());
        assertThat(message.getValue().type()).isEqualTo(OutboxMessageTypes.NOTIFY_ORDER_PLACED);
        assertThat(message.getValue().payload()).containsEntry("userId", userId.toString());
        verify(eventPublisher).publish(any());
    }

    @Test
    void persistsOrderBeforeReservingStockAndClosingTheCart() {
        StepVerifier.create(useCase.execute(new PlaceOrderCommand(userId, checkoutId)))
            .expectNextCount(1)
            .verifyComplete();

        InOrder order = inOrder(orderRepository, inventoryReservationPort, cartCheckoutPort, checkoutDataPort);
        order.verify(orderRepository).save(any());
        order.verify(inventoryReservationPort).reserve(any(), anyList());
        order.verify(cartCheckoutPort).markCartAsCheckedOut(cartId);
        order.verify(checkoutDataPort).completeCheckout(checkoutId);
    }

    @Test
    void insufficientStockAbortsPlacementWithoutClosingCartOrPublishing() {
        when(inventoryReservationPort.reserve(any(), anyList()))
            .thenReturn(Mono.error(new IllegalStateException("Insufficient stock")));

        StepVerifier.create(useCase.execute(new PlaceOrderCommand(userId, checkoutId)))
            .expectErrorMessage("Insufficient stock")
            .verify();

        verify(cartCheckoutPort, never()).markCartAsCheckedOut(any());
        verify(checkoutDataPort, never()).completeCheckout(any());
        verify(outboxWriter, never()).enqueue(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void rejectsSecondOrderForTheSameCheckout() {
        when(orderRepository.existsByCheckoutId(checkoutId)).thenReturn(Mono.just(true));

        StepVerifier.create(useCase.execute(new PlaceOrderCommand(userId, checkoutId)))
            .expectErrorMatches(e -> e instanceof OrderDomainException ode
                && ode.getErrorCode() == OrderErrorCode.DUPLICATE_ORDER)
            .verify();

        verify(orderRepository, never()).save(any());
        verify(inventoryReservationPort, never()).reserve(any(), anyList());
    }

    @Test
    void usesOrderIdForReservations() {
        StepVerifier.create(useCase.execute(new PlaceOrderCommand(userId, checkoutId)))
            .assertNext(result -> verify(inventoryReservationPort).reserve(eq(result.id()), anyList()))
            .verifyComplete();
    }

    private CheckoutDataPort.CheckoutData checkoutData() {
        var address = new CheckoutDataPort.AddressData("1 MG Road", null, "Bengaluru", "KA", "IN", "560001",
            "Test User", "9999999999");
        var item = new CheckoutDataPort.CheckoutItemData(productId, "Bat", "SKU-1", 2,
            new BigDecimal("100.00"), new BigDecimal("200.00"), null);
        return new CheckoutDataPort.CheckoutData(checkoutId, cartId, UUID.randomUUID(), null, List.of(item),
            new BigDecimal("200.00"), new BigDecimal("5.00"), new BigDecimal("36.00"), BigDecimal.ZERO,
            new BigDecimal("241.00"), "INR", address, null);
    }
}
