package com.dsports.pricing.application.usecase;

import com.dsports.pricing.application.command.CreatePriceCommand;
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
class CreatePriceUseCaseTest {

    @Mock
    private PriceRepository priceRepository;

    private CreatePriceUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CreatePriceUseCase(priceRepository);
    }

    @Test
    void shouldCreatePriceSuccessfully() {
        var productId = UUID.randomUUID();
        var effectiveFrom = Instant.now();
        var command = new CreatePriceCommand(
                productId,
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(80),
                "INR",
                effectiveFrom,
                null);

        when(priceRepository.existsByProductIdAndCurrencyAndStatus(any(), any(), any()))
                .thenReturn(Mono.just(false));
        when(priceRepository.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> {
                    assertThat(result).isNotNull();
                    assertThat(result.productId()).isEqualTo(productId);
                    assertThat(result.mrp()).isEqualByComparingTo(BigDecimal.valueOf(100));
                    assertThat(result.sellingPrice()).isEqualByComparingTo(BigDecimal.valueOf(80));
                    assertThat(result.currency()).isEqualTo("INR");
                    assertThat(result.effectiveFrom()).isEqualTo(effectiveFrom);
                    assertThat(result.effectiveTo()).isNull();
                    assertThat(result.status()).isEqualTo("DRAFT");
                    assertThat(result.version()).isZero();
                    assertThat(result.id()).isNotNull();
                    assertThat(result.createdAt()).isNotNull();
                    assertThat(result.updatedAt()).isNotNull();
                })
                .verifyComplete();

        verify(priceRepository).save(any());
    }

    @Test
    void shouldRejectOverlappingActivePrice() {
        var command = new CreatePriceCommand(
                UUID.randomUUID(),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(80),
                "INR",
                Instant.now(),
                null);

        when(priceRepository.existsByProductIdAndCurrencyAndStatus(any(), any(), any()))
                .thenReturn(Mono.just(true));

        StepVerifier.create(useCase.execute(command))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PricingDomainException.class);
                    assertThat(((PricingDomainException) e).getErrorCode())
                            .isEqualTo(PricingErrorCode.OVERLAPPING_ACTIVE_PRICE);
                })
                .verify();

        verify(priceRepository, never()).save(any());
    }

    @Test
    void shouldRejectSellingPriceExceedingMrp() {
        var command = new CreatePriceCommand(
                UUID.randomUUID(),
                BigDecimal.valueOf(80),
                BigDecimal.valueOf(100),
                "INR",
                Instant.now(),
                null);

        when(priceRepository.existsByProductIdAndCurrencyAndStatus(any(), any(), any()))
                .thenReturn(Mono.just(false));

        StepVerifier.create(useCase.execute(command))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PricingDomainException.class);
                    assertThat(((PricingDomainException) e).getErrorCode())
                            .isEqualTo(PricingErrorCode.INVALID_PRICE);
                })
                .verify();

        verify(priceRepository, never()).save(any());
    }

    @Test
    void shouldRejectInvalidCurrency() {
        var command = new CreatePriceCommand(
                UUID.randomUUID(),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(80),
                "XYZ",
                Instant.now(),
                null);

        StepVerifier.create(useCase.execute(command))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PricingDomainException.class);
                    assertThat(((PricingDomainException) e).getErrorCode())
                            .isEqualTo(PricingErrorCode.INVALID_CURRENCY);
                })
                .verify();

        verify(priceRepository, never()).save(any());
    }
}
