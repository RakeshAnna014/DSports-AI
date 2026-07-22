package com.dsports.pricing.application.usecase;

import com.dsports.pricing.application.command.UpdatePriceCommand;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UpdatePriceUseCaseTest {

    @Mock
    private PriceRepository priceRepository;

    private UpdatePriceUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new UpdatePriceUseCase(priceRepository);
    }

    @Test
    void shouldUpdatePriceSuccessfully() {
        var priceId = UUID.randomUUID();
        var productId = ProductId.fromUUID(UUID.randomUUID());
        var mrp = Money.from(200);
        var sellingPrice = Money.from(150);
        var currency = Currency.from("INR");
        var price = Price.create(productId, mrp, sellingPrice, currency, EffectiveDate.immediate());
        price.clearDomainEvents();

        var effectiveFrom = Instant.now();
        var command = new UpdatePriceCommand(priceId, BigDecimal.valueOf(250), BigDecimal.valueOf(200),
                effectiveFrom, null);

        when(priceRepository.findById(PriceId.fromUUID(priceId)))
                .thenReturn(Mono.just(price));
        when(priceRepository.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> {
                    assertThat(result).isNotNull();
                    assertThat(result.mrp()).isEqualByComparingTo(BigDecimal.valueOf(250));
                    assertThat(result.sellingPrice()).isEqualByComparingTo(BigDecimal.valueOf(200));
                })
                .verifyComplete();

        verify(priceRepository).findById(PriceId.fromUUID(priceId));
        verify(priceRepository).save(any());
    }

    @Test
    void shouldRejectUpdateWhenPriceNotFound() {
        var command = new UpdatePriceCommand(UUID.randomUUID(), BigDecimal.valueOf(250),
                BigDecimal.valueOf(200), Instant.now(), null);

        when(priceRepository.findById(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PricingDomainException.class);
                    assertThat(((PricingDomainException) e).getErrorCode())
                            .isEqualTo(PricingErrorCode.PRICE_NOT_FOUND);
                })
                .verify();

        verify(priceRepository, never()).save(any());
    }

    @Test
    void shouldRejectSellingPriceExceedingMrp() {
        var productId = ProductId.fromUUID(UUID.randomUUID());
        var price = Price.create(productId, Money.from(200), Money.from(150),
                Currency.from("INR"), EffectiveDate.immediate());
        price.clearDomainEvents();

        var command = new UpdatePriceCommand(UUID.randomUUID(), BigDecimal.valueOf(100),
                BigDecimal.valueOf(200), Instant.now(), null);

        when(priceRepository.findById(any())).thenReturn(Mono.just(price));

        StepVerifier.create(useCase.execute(command))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PricingDomainException.class);
                    assertThat(((PricingDomainException) e).getErrorCode())
                            .isEqualTo(PricingErrorCode.INVALID_PRICE);
                })
                .verify();

        verify(priceRepository, never()).save(any());
    }
}
