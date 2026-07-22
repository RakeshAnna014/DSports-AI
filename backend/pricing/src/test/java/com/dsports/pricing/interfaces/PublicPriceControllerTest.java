package com.dsports.pricing.interfaces;

import com.dsports.pricing.application.result.PriceResult;
import com.dsports.pricing.application.usecase.GetPriceUseCase;
import com.dsports.pricing.application.usecase.GetPricesUseCase;
import com.dsports.pricing.domain.model.PriceId;
import com.dsports.pricing.domain.model.ProductId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicPriceControllerTest {

    @Mock
    private GetPricesUseCase getPricesUseCase;

    @Mock
    private GetPriceUseCase getPriceUseCase;

    @InjectMocks
    private PublicPriceController controller;

    @Test
    void shouldReturnAllPrices() {
        var result = new PriceResult(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(200),
                BigDecimal.valueOf(150), "INR", Instant.now(), null, "DRAFT", 0,
                Instant.now(), Instant.now());
        when(getPricesUseCase.execute()).thenReturn(Flux.just(result));

        StepVerifier.create(controller.getPrices(null))
                .expectNextMatches(r -> r.currency().equals("INR") && r.status().equals("DRAFT"))
                .verifyComplete();
    }

    @Test
    void shouldReturnPricesByProductId() {
        var productId = UUID.randomUUID();
        var result = new PriceResult(UUID.randomUUID(), productId, BigDecimal.valueOf(200),
                BigDecimal.valueOf(150), "INR", Instant.now(), null, "ACTIVE", 0,
                Instant.now(), Instant.now());
        when(getPricesUseCase.execute(ProductId.fromUUID(productId))).thenReturn(Flux.just(result));

        StepVerifier.create(controller.getPrices(productId))
                .expectNextMatches(r -> r.productId().equals(productId))
                .verifyComplete();
    }

    @Test
    void shouldReturnPriceById() {
        var priceId = UUID.randomUUID();
        var result = new PriceResult(priceId, UUID.randomUUID(), BigDecimal.valueOf(200),
                BigDecimal.valueOf(150), "INR", Instant.now(), null, "ACTIVE", 0,
                Instant.now(), Instant.now());
        when(getPriceUseCase.execute(PriceId.fromUUID(priceId))).thenReturn(Mono.just(result));

        StepVerifier.create(controller.getPrice(priceId))
                .expectNextMatches(r -> r.id().equals(priceId))
                .verifyComplete();
    }
}
