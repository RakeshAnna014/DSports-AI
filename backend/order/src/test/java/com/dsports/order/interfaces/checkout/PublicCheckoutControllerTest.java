package com.dsports.order.interfaces.checkout;

import com.dsports.order.application.checkout.command.CreateCheckoutCommand;
import com.dsports.order.application.checkout.command.SelectDeliveryMethodCommand;
import com.dsports.order.application.checkout.command.SelectShippingAddressCommand;
import com.dsports.order.application.checkout.result.CheckoutItemResult;
import com.dsports.order.application.checkout.result.CheckoutResult;
import com.dsports.order.application.checkout.usecase.CancelCheckoutUseCase;
import com.dsports.order.application.checkout.usecase.CreateCheckoutUseCase;
import com.dsports.order.application.checkout.usecase.GetCheckoutUseCase;
import com.dsports.order.application.checkout.usecase.SelectDeliveryMethodUseCase;
import com.dsports.order.application.checkout.usecase.SelectShippingAddressUseCase;
import com.dsports.order.application.checkout.usecase.ValidateCheckoutUseCase;
import com.dsports.order.interfaces.checkout.dto.CheckoutResponse;
import com.dsports.order.interfaces.checkout.dto.CreateCheckoutResponse;
import com.dsports.order.interfaces.checkout.dto.SelectAddressRequest;
import com.dsports.order.interfaces.checkout.dto.SelectDeliveryMethodRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicCheckoutControllerTest {

    @Mock
    private CreateCheckoutUseCase createCheckoutUseCase;
    @Mock
    private GetCheckoutUseCase getCheckoutUseCase;
    @Mock
    private SelectShippingAddressUseCase selectShippingAddressUseCase;
    @Mock
    private SelectDeliveryMethodUseCase selectDeliveryMethodUseCase;
    @Mock
    private ValidateCheckoutUseCase validateCheckoutUseCase;
    @Mock
    private CancelCheckoutUseCase cancelCheckoutUseCase;
    @Mock
    private Authentication authentication;

    @InjectMocks
    private PublicCheckoutController controller;

    private final UUID customerId = UUID.randomUUID();
    private final UUID checkoutId = UUID.randomUUID();
    private final UUID cartId = UUID.randomUUID();

    private CheckoutResult sampleResult() {
        return new CheckoutResult(
            checkoutId, customerId, cartId, "PENDING", null, null, null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, "INR", null, Instant.now().plusSeconds(1800), null,
            0, Instant.now(), Instant.now(), List.of(
                new CheckoutItemResult(
                    UUID.randomUUID(), UUID.randomUUID(), "Cricket Bat",
                    "CB-001", 2, BigDecimal.valueOf(100), BigDecimal.valueOf(200),
                    null, Instant.now())
            ));
    }

    @Test
    void shouldCreateCheckout() {
        when(authentication.getPrincipal()).thenReturn(customerId.toString());
        when(createCheckoutUseCase.execute(any(CreateCheckoutCommand.class)))
            .thenReturn(Mono.just(sampleResult()));

        StepVerifier.create(controller.createCheckout(authentication))
            .assertNext(response -> {
                assertThat(response.id()).isEqualTo(checkoutId);
                assertThat(response.status()).isEqualTo("PENDING");
            })
            .verifyComplete();
    }

    @Test
    void shouldGetCheckout() {
        when(authentication.getPrincipal()).thenReturn(customerId.toString());
        when(getCheckoutUseCase.execute(checkoutId, customerId))
            .thenReturn(Mono.just(sampleResult()));

        StepVerifier.create(controller.getCheckout(checkoutId, authentication))
            .assertNext(response -> {
                assertThat(response.id()).isEqualTo(checkoutId);
                assertThat(response.status()).isEqualTo("PENDING");
            })
            .verifyComplete();
    }

    @Test
    void shouldGetActiveCheckout() {
        when(authentication.getPrincipal()).thenReturn(customerId.toString());
        when(getCheckoutUseCase.getActiveCheckout(customerId))
            .thenReturn(Mono.just(sampleResult()));

        StepVerifier.create(controller.getActiveCheckout(authentication))
            .assertNext(response -> {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(response.getBody()).isNotNull();
                assertThat(response.getBody().id()).isEqualTo(checkoutId);
            })
            .verifyComplete();
    }

    @Test
    void shouldReturnNotFoundWhenNoActiveCheckout() {
        when(authentication.getPrincipal()).thenReturn(customerId.toString());
        when(getCheckoutUseCase.getActiveCheckout(customerId))
            .thenReturn(Mono.empty());

        StepVerifier.create(controller.getActiveCheckout(authentication))
            .assertNext(response -> {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            })
            .verifyComplete();
    }

    @Test
    void shouldSelectAddress() {
        var addressId = UUID.randomUUID();
        var request = new SelectAddressRequest(addressId);
        when(authentication.getPrincipal()).thenReturn(customerId.toString());
        when(selectShippingAddressUseCase.execute(any(SelectShippingAddressCommand.class)))
            .thenReturn(Mono.just(sampleResult()));

        StepVerifier.create(controller.selectAddress(checkoutId, request, authentication))
            .assertNext(response -> {
                assertThat(response.id()).isEqualTo(checkoutId);
            })
            .verifyComplete();
    }

    @Test
    void shouldSelectDeliveryMethod() {
        var request = new SelectDeliveryMethodRequest("STANDARD");
        when(authentication.getPrincipal()).thenReturn(customerId.toString());
        when(selectDeliveryMethodUseCase.execute(any(SelectDeliveryMethodCommand.class)))
            .thenReturn(Mono.just(sampleResult()));

        StepVerifier.create(controller.selectDeliveryMethod(checkoutId, request, authentication))
            .assertNext(response -> {
                assertThat(response.id()).isEqualTo(checkoutId);
            })
            .verifyComplete();
    }

    @Test
    void shouldValidateCheckout() {
        when(authentication.getPrincipal()).thenReturn(customerId.toString());
        when(validateCheckoutUseCase.execute(checkoutId, customerId))
            .thenReturn(Mono.just(sampleResult()));

        StepVerifier.create(controller.validateCheckout(checkoutId, authentication))
            .assertNext(response -> {
                assertThat(response.id()).isEqualTo(checkoutId);
            })
            .verifyComplete();
    }

    @Test
    void shouldCancelCheckout() {
        when(authentication.getPrincipal()).thenReturn(customerId.toString());
        when(cancelCheckoutUseCase.execute(checkoutId, customerId))
            .thenReturn(Mono.just(sampleResult()));

        StepVerifier.create(controller.cancelCheckout(checkoutId, authentication))
            .assertNext(response -> {
                assertThat(response.id()).isEqualTo(checkoutId);
            })
            .verifyComplete();
    }
}
