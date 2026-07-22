package com.dsports.pricing.application.usecase;

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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GetPriceUseCaseTest {

    @Mock
    private PriceRepository priceRepository;

    private GetPriceUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetPriceUseCase(priceRepository);
    }

    @Test
    void shouldReturnPriceWhenFound() {
        var productId = ProductId.fromUUID(UUID.randomUUID());
        var price = Price.create(productId, Money.from(200), Money.from(150),
                Currency.from("INR"), EffectiveDate.immediate());

        when(priceRepository.findById(price.getId())).thenReturn(Mono.just(price));

        StepVerifier.create(useCase.execute(price.getId()))
                .assertNext(result -> {
                    assertThat(result).isNotNull();
                    assertThat(result.id()).isEqualTo(price.getId().value());
                    assertThat(result.mrp()).isEqualByComparingTo(java.math.BigDecimal.valueOf(200));
                    assertThat(result.sellingPrice()).isEqualByComparingTo(java.math.BigDecimal.valueOf(150));
                    assertThat(result.currency()).isEqualTo("INR");
                    assertThat(result.status()).isEqualTo("DRAFT");
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnErrorWhenPriceNotFound() {
        var priceId = PriceId.generate();

        when(priceRepository.findById(priceId)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(priceId))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PricingDomainException.class);
                    assertThat(((PricingDomainException) e).getErrorCode())
                            .isEqualTo(PricingErrorCode.PRICE_NOT_FOUND);
                })
                .verify();
    }
}
