package com.dsports.pricing.application.usecase;

import com.dsports.pricing.application.command.ArchivePriceCommand;
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
class ArchivePriceUseCaseTest {

    @Mock
    private PriceRepository priceRepository;

    private ArchivePriceUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ArchivePriceUseCase(priceRepository);
    }

    @Test
    void shouldArchiveActivePrice() {
        var priceId = UUID.randomUUID();
        var productId = ProductId.fromUUID(UUID.randomUUID());
        var price = Price.create(productId, Money.from(200), Money.from(150),
                Currency.from("INR"), EffectiveDate.immediate());
        price.activate();
        price.clearDomainEvents();

        when(priceRepository.findById(PriceId.fromUUID(priceId)))
                .thenReturn(Mono.just(price));
        when(priceRepository.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(new ArchivePriceCommand(priceId)))
                .assertNext(result -> {
                    assertThat(result).isNotNull();
                    assertThat(result.status()).isEqualTo("ARCHIVED");
                })
                .verifyComplete();

        verify(priceRepository).save(any());
    }

    @Test
    void shouldArchiveDraftPrice() {
        var priceId = UUID.randomUUID();
        var productId = ProductId.fromUUID(UUID.randomUUID());
        var price = Price.create(productId, Money.from(200), Money.from(150),
                Currency.from("INR"), EffectiveDate.immediate());
        price.clearDomainEvents();

        when(priceRepository.findById(PriceId.fromUUID(priceId)))
                .thenReturn(Mono.just(price));
        when(priceRepository.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(new ArchivePriceCommand(priceId)))
                .assertNext(result -> {
                    assertThat(result).isNotNull();
                    assertThat(result.status()).isEqualTo("ARCHIVED");
                })
                .verifyComplete();

        verify(priceRepository).save(any());
    }

    @Test
    void shouldRejectArchiveWhenPriceNotFound() {
        when(priceRepository.findById(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(new ArchivePriceCommand(UUID.randomUUID())))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PricingDomainException.class);
                    assertThat(((PricingDomainException) e).getErrorCode())
                            .isEqualTo(PricingErrorCode.PRICE_NOT_FOUND);
                })
                .verify();

        verify(priceRepository, never()).save(any());
    }
}
