package com.dsports.pricing.application.usecase;

import com.dsports.pricing.application.command.ActivatePriceCommand;
import com.dsports.pricing.application.port.PriceRepository;
import com.dsports.pricing.application.result.PriceResult;
import com.dsports.pricing.domain.exception.PricingDomainException;
import com.dsports.pricing.domain.exception.PricingErrorCode;
import com.dsports.pricing.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActivatePriceUseCaseTest {

    @Mock
    private PriceRepository priceRepository;

    private ActivatePriceUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ActivatePriceUseCase(priceRepository);
    }

    @Test
    void shouldActivateDraftPrice() {
        var priceId = UUID.randomUUID();
        var productId = ProductId.fromUUID(UUID.randomUUID());
        var price = Price.create(productId, Money.from(200), Money.from(150),
                Currency.from("INR"), EffectiveDate.immediate());
        price.clearDomainEvents();

        when(priceRepository.findById(PriceId.fromUUID(priceId)))
                .thenReturn(Mono.just(price));
        when(priceRepository.save(any())).thenReturn(Mono.empty());
        when(priceRepository.deactivateActivePrices(any(), any(), any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(new ActivatePriceCommand(priceId)))
                .assertNext(result -> {
                    assertThat(result).isNotNull();
                    assertThat(result.status()).isEqualTo("ACTIVE");
                })
                .verifyComplete();

        verify(priceRepository).save(any());
    }

    @Test
    void shouldRejectActivateWhenPriceNotFound() {
        var priceId = UUID.randomUUID();
        when(priceRepository.findById(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(new ActivatePriceCommand(priceId)))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PricingDomainException.class);
                    assertThat(((PricingDomainException) e).getErrorCode())
                            .isEqualTo(PricingErrorCode.PRICE_NOT_FOUND);
                })
                .verify();

        verify(priceRepository, never()).save(any());
    }

    @Test
    void shouldRejectActivateWhenArchived() {
        var priceId = UUID.randomUUID();
        var productId = ProductId.fromUUID(UUID.randomUUID());
        var price = Price.create(productId, Money.from(200), Money.from(150),
                Currency.from("INR"), EffectiveDate.immediate());
        price.archive();
        price.clearDomainEvents();

        when(priceRepository.findById(PriceId.fromUUID(priceId)))
                .thenReturn(Mono.just(price));

        StepVerifier.create(useCase.execute(new ActivatePriceCommand(priceId)))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PricingDomainException.class);
                    assertThat(((PricingDomainException) e).getErrorCode())
                            .isEqualTo(PricingErrorCode.CANNOT_ACTIVATE_ARCHIVED);
                })
                .verify();

        verify(priceRepository, never()).save(any());
    }
}
