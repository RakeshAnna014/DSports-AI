package com.dsports.order.application.checkout.usecase;

import com.dsports.order.application.checkout.command.CreateCheckoutCommand;
import com.dsports.order.application.checkout.port.CartPort;
import com.dsports.order.application.checkout.port.CheckoutRepository;
import com.dsports.order.application.checkout.port.EventPublisher;
import com.dsports.order.application.checkout.port.InventoryPort;
import com.dsports.order.application.checkout.port.PricingPort;
import com.dsports.order.domain.checkout.exception.CheckoutDomainException;
import com.dsports.order.domain.checkout.exception.CheckoutErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateCheckoutUseCaseTest {

    @Mock
    private CheckoutRepository checkoutRepository;
    @Mock
    private CartPort cartPort;
    @Mock
    private InventoryPort inventoryPort;
    @Mock
    private PricingPort pricingPort;
    @Mock
    private EventPublisher eventPublisher;

    private CreateCheckoutUseCase useCase;
    private UUID customerId;
    private UUID cartId;
    private UUID productId;
    private CreateCheckoutCommand command;
    private CartPort.CartData cartData;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        cartId = UUID.randomUUID();
        productId = UUID.randomUUID();
        command = new CreateCheckoutCommand(customerId);
        var item = new CartPort.CartItemData(
            productId, "Cricket Bat", "SKU-001", 2,
            BigDecimal.valueOf(100.00), "INR", "http://example.com/img.jpg");
        cartData = new CartPort.CartData(cartId, List.of(item));
        useCase = new CreateCheckoutUseCase(checkoutRepository, cartPort, inventoryPort, pricingPort, eventPublisher);
    }

    @Test
    void shouldCreateCheckoutSuccessfully() {
        when(checkoutRepository.existsActiveCheckout(customerId)).thenReturn(Mono.just(false));
        when(cartPort.getActiveCart(customerId)).thenReturn(Mono.just(cartData));
        when(inventoryPort.checkAvailability(productId, 2))
            .thenReturn(Mono.just(new InventoryPort.InventoryResult(productId, 10, true)));
        when(pricingPort.getActivePrice(productId))
            .thenReturn(Mono.just(new PricingPort.PriceResult(productId, BigDecimal.valueOf(100.00), "INR")));
        when(checkoutRepository.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command))
            .assertNext(result -> {
                assertThat(result).isNotNull();
                assertThat(result.cartId()).isEqualTo(cartId);
                assertThat(result.status()).isEqualTo("PENDING");
                assertThat(result.subtotal()).isEqualByComparingTo("200.00");
                assertThat(result.taxAmount()).isEqualByComparingTo("36.00");
                assertThat(result.deliveryCharge()).isEqualByComparingTo("0.00");
                assertThat(result.discountAmount()).isEqualByComparingTo("0.00");
                assertThat(result.totalAmount()).isEqualByComparingTo("236.00");
                assertThat(result.currency()).isEqualTo("INR");
                assertThat(result.items()).hasSize(1);
            })
            .verifyComplete();

        verify(checkoutRepository).save(any());
        verify(eventPublisher, atLeastOnce()).publish(any());
    }

    @Test
    void shouldRejectWhenActiveCheckoutExists() {
        when(checkoutRepository.existsActiveCheckout(customerId)).thenReturn(Mono.just(true));

        StepVerifier.create(useCase.execute(command))
            .expectErrorSatisfies(e -> {
                assertThat(e).isInstanceOf(CheckoutDomainException.class);
                assertThat(((CheckoutDomainException) e).getErrorCode())
                    .isEqualTo(CheckoutErrorCode.CHECKOUT_ALREADY_TERMINAL);
            })
            .verify();

        verify(checkoutRepository, never()).save(any());
    }

    @Test
    void shouldRejectWhenCartNotFound() {
        when(checkoutRepository.existsActiveCheckout(customerId)).thenReturn(Mono.just(false));
        when(cartPort.getActiveCart(customerId)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command))
            .expectErrorSatisfies(e -> {
                assertThat(e).isInstanceOf(CheckoutDomainException.class);
                assertThat(((CheckoutDomainException) e).getErrorCode())
                    .isEqualTo(CheckoutErrorCode.CART_NOT_FOUND);
            })
            .verify();
    }

    @Test
    void shouldRejectWhenCartIsEmpty() {
        var emptyCart = new CartPort.CartData(cartId, List.of());
        when(checkoutRepository.existsActiveCheckout(customerId)).thenReturn(Mono.just(false));
        when(cartPort.getActiveCart(customerId)).thenReturn(Mono.just(emptyCart));

        StepVerifier.create(useCase.execute(command))
            .expectErrorSatisfies(e -> {
                assertThat(e).isInstanceOf(CheckoutDomainException.class);
                assertThat(((CheckoutDomainException) e).getErrorCode())
                    .isEqualTo(CheckoutErrorCode.CART_EMPTY);
            })
            .verify();
    }

    @Test
    void shouldRejectWhenInsufficientStock() {
        when(checkoutRepository.existsActiveCheckout(customerId)).thenReturn(Mono.just(false));
        when(cartPort.getActiveCart(customerId)).thenReturn(Mono.just(cartData));
        when(inventoryPort.checkAvailability(productId, 2))
            .thenReturn(Mono.just(new InventoryPort.InventoryResult(productId, 1, false)));
        when(pricingPort.getActivePrice(productId))
            .thenReturn(Mono.just(new PricingPort.PriceResult(productId, BigDecimal.valueOf(100.00), "INR")));

        StepVerifier.create(useCase.execute(command))
            .expectErrorSatisfies(e -> {
                assertThat(e).isInstanceOf(CheckoutDomainException.class);
                assertThat(((CheckoutDomainException) e).getErrorCode())
                    .isEqualTo(CheckoutErrorCode.ITEM_OUT_OF_STOCK);
            })
            .verify();

        verify(checkoutRepository, never()).save(any());
    }

    @Test
    void shouldRejectWhenNoActivePrice() {
        when(checkoutRepository.existsActiveCheckout(customerId)).thenReturn(Mono.just(false));
        when(cartPort.getActiveCart(customerId)).thenReturn(Mono.just(cartData));
        when(inventoryPort.checkAvailability(productId, 2))
            .thenReturn(Mono.just(new InventoryPort.InventoryResult(productId, 10, true)));
        when(pricingPort.getActivePrice(productId)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command))
            .expectErrorSatisfies(e -> {
                assertThat(e).isInstanceOf(CheckoutDomainException.class);
                assertThat(((CheckoutDomainException) e).getErrorCode())
                    .isEqualTo(CheckoutErrorCode.PRICE_NOT_FOUND);
            })
            .verify();

        verify(checkoutRepository, never()).save(any());
    }
}
