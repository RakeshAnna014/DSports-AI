package com.dsports.pricing.application.usecase;

import com.dsports.pricing.application.port.PriceRepository;
import com.dsports.pricing.application.result.PriceResult;
import com.dsports.pricing.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GetPricesUseCaseTest {

    @Mock
    private PriceRepository priceRepository;

    private GetPricesUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetPricesUseCase(priceRepository);
    }

    @Test
    void shouldReturnAllPrices() {
        var productId = ProductId.fromUUID(UUID.randomUUID());
        var price1 = Price.create(productId, Money.from(200), Money.from(150),
                Currency.from("INR"), EffectiveDate.immediate());
        var price2 = Price.create(productId, Money.from(300), Money.from(250),
                Currency.from("USD"), EffectiveDate.immediate());

        when(priceRepository.findAll()).thenReturn(Flux.just(price1, price2));

        StepVerifier.create(useCase.execute())
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void shouldReturnPricesByProductId() {
        var productId = ProductId.fromUUID(UUID.randomUUID());
        var price = Price.create(productId, Money.from(200), Money.from(150),
                Currency.from("INR"), EffectiveDate.immediate());

        when(priceRepository.findByProductId(productId)).thenReturn(Flux.just(price));

        StepVerifier.create(useCase.execute(productId))
                .assertNext(result -> {
                    assertThat(result).isNotNull();
                    assertThat(result.productId()).isEqualTo(productId.value());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyWhenNoPrices() {
        when(priceRepository.findAll()).thenReturn(Flux.empty());

        StepVerifier.create(useCase.execute())
                .expectNextCount(0)
                .verifyComplete();
    }
}
