package com.dsports.order.application.checkout.usecase;

import com.dsports.order.application.checkout.port.CheckoutRepository;
import com.dsports.order.application.checkout.port.EventPublisher;
import com.dsports.order.application.checkout.port.InventoryPort;
import com.dsports.order.domain.checkout.exception.CheckoutDomainException;
import com.dsports.order.domain.checkout.exception.CheckoutErrorCode;
import com.dsports.order.domain.checkout.model.CheckoutId;
import com.dsports.order.domain.checkout.model.Checkout;
import com.dsports.order.domain.checkout.model.CheckoutId;
import com.dsports.order.domain.checkout.model.CheckoutItem;
import com.dsports.order.domain.checkout.model.CheckoutItemId;
import com.dsports.order.domain.checkout.model.DeliveryMethod;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ValidateCheckoutUseCaseTest {

    @Mock
    private CheckoutRepository checkoutRepository;
    @Mock
    private InventoryPort inventoryPort;
    @Mock
    private EventPublisher eventPublisher;

    private ValidateCheckoutUseCase useCase;
    private UUID customerId;
    private UUID checkoutId;
    private Checkout checkout;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        checkoutId = UUID.randomUUID();
        useCase = new ValidateCheckoutUseCase(checkoutRepository, inventoryPort, eventPublisher);

        var id = CheckoutId.fromUUID(checkoutId);
        var items = List.of(
            new CheckoutItem(CheckoutItemId.generate(), id,
                UUID.randomUUID().toString(), "Cricket Bat", "CB-001", 2,
                BigDecimal.valueOf(100.00), BigDecimal.valueOf(200.00), null, Instant.now())
        );
        checkout = Checkout.create(id, customerId, UUID.randomUUID(), items);
        checkout.selectShippingAddress(UUID.randomUUID());
        checkout.selectDeliveryMethod(DeliveryMethod.STANDARD);
        checkout.clearDomainEvents();
    }

    @Test
    void shouldValidateCheckoutSuccessfully() {
        when(checkoutRepository.findById(any(CheckoutId.class)))
            .thenAnswer(invocation -> Mono.just(checkout));
        when(inventoryPort.checkAvailability(any(), anyInt()))
            .thenReturn(Mono.just(new InventoryPort.InventoryResult(UUID.randomUUID(), 10, true)));
        when(checkoutRepository.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(checkoutId, customerId))
            .assertNext(result -> {
                assertThat(result).isNotNull();
                assertThat(result.status()).isEqualTo("VALIDATED");
                assertThat(result.validatedAt()).isNotNull();
            })
            .verifyComplete();

        verify(checkoutRepository, atLeast(2)).findById(any(CheckoutId.class));
        verify(checkoutRepository).save(any());
    }

    @Test
    void shouldRejectWhenCheckoutNotFound() {
        when(checkoutRepository.findById(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(checkoutId, customerId))
            .expectErrorSatisfies(e -> {
                assertThat(e).isInstanceOf(CheckoutDomainException.class);
                assertThat(((CheckoutDomainException) e).getErrorCode())
                    .isEqualTo(CheckoutErrorCode.CHECKOUT_NOT_FOUND);
            })
            .verify();
    }

    @Test
    void shouldRejectWhenCustomerMismatch() {
        var wrongCustomer = UUID.randomUUID();
        when(checkoutRepository.findById(any())).thenReturn(Mono.just(checkout));

        StepVerifier.create(useCase.execute(checkoutId, wrongCustomer))
            .expectErrorSatisfies(e -> {
                assertThat(e).isInstanceOf(CheckoutDomainException.class);
                assertThat(((CheckoutDomainException) e).getErrorCode())
                    .isEqualTo(CheckoutErrorCode.CHECKOUT_NOT_OWNED_BY_CUSTOMER);
            })
            .verify();
    }

    @Test
    void shouldRejectWhenInsufficientStock() {
        when(checkoutRepository.findById(any())).thenReturn(Mono.just(checkout));
        when(inventoryPort.checkAvailability(any(), anyInt()))
            .thenReturn(Mono.just(new InventoryPort.InventoryResult(UUID.randomUUID(), 1, false)));

        StepVerifier.create(useCase.execute(checkoutId, customerId))
            .expectErrorSatisfies(e -> {
                assertThat(e).isInstanceOf(CheckoutDomainException.class);
                assertThat(((CheckoutDomainException) e).getErrorCode())
                    .isEqualTo(CheckoutErrorCode.ITEM_OUT_OF_STOCK);
            })
            .verify();
    }
}
