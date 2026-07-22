package com.dsports.pricing.application.usecase;

import com.dsports.pricing.application.command.SchedulePriceCommand;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulePriceUseCaseTest {

    @Mock
    private PriceRepository priceRepository;

    private SchedulePriceUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SchedulePriceUseCase(priceRepository);
    }

    @Test
    void shouldScheduleDraftPrice() {
        var priceId = UUID.randomUUID();
        var productId = ProductId.fromUUID(UUID.randomUUID());
        var price = Price.create(productId, Money.from(200), Money.from(150),
                Currency.from("INR"), EffectiveDate.immediate());
        price.clearDomainEvents();

        var scheduledFrom = Instant.now().plusSeconds(86400);

        when(priceRepository.findById(PriceId.fromUUID(priceId)))
                .thenReturn(Mono.just(price));
        when(priceRepository.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(new SchedulePriceCommand(priceId, scheduledFrom)))
                .assertNext(result -> {
                    assertThat(result).isNotNull();
                    assertThat(result.status()).isEqualTo("SCHEDULED");
                    assertThat(result.effectiveFrom()).isEqualTo(scheduledFrom);
                })
                .verifyComplete();

        verify(priceRepository).save(any());
    }

    @Test
    void shouldRejectScheduleWhenPriceNotFound() {
        when(priceRepository.findById(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(
                        new SchedulePriceCommand(UUID.randomUUID(), Instant.now().plusSeconds(86400))))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PricingDomainException.class);
                    assertThat(((PricingDomainException) e).getErrorCode())
                            .isEqualTo(PricingErrorCode.PRICE_NOT_FOUND);
                })
                .verify();

        verify(priceRepository, never()).save(any());
    }

    @Test
    void shouldRejectScheduleNonDraft() {
        var priceId = UUID.randomUUID();
        var productId = ProductId.fromUUID(UUID.randomUUID());
        var price = Price.create(productId, Money.from(200), Money.from(150),
                Currency.from("INR"), EffectiveDate.immediate());
        price.activate();
        price.clearDomainEvents();

        when(priceRepository.findById(PriceId.fromUUID(priceId)))
                .thenReturn(Mono.just(price));

        StepVerifier.create(useCase.execute(
                        new SchedulePriceCommand(priceId, Instant.now().plusSeconds(86400))))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PricingDomainException.class);
                    assertThat(((PricingDomainException) e).getErrorCode())
                            .isEqualTo(PricingErrorCode.CANNOT_SCHEDULE_NON_DRAFT);
                })
                .verify();

        verify(priceRepository, never()).save(any());
    }
}
