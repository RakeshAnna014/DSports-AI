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
    void publicListingHidesDraftScheduledAndArchivedPrices() {
        var active = price("ACTIVE");
        when(getPricesUseCase.execute()).thenReturn(Flux.just(price("DRAFT"), active, price("SCHEDULED"), price("ARCHIVED")));

        StepVerifier.create(controller.getPrices(null, null))
                .expectNextMatches(r -> r.id().equals(active.id()))
                .verifyComplete();
    }

    @Test
    void unpublishedPriceIsNotFoundById() {
        var draft = price("DRAFT");
        when(getPriceUseCase.execute(PriceId.fromUUID(draft.id()))).thenReturn(Mono.just(draft));

        StepVerifier.create(controller.getPrice(draft.id()))
                .expectError(com.dsports.pricing.domain.exception.PricingDomainException.class)
                .verify();
    }

    private static PriceResult price(String status) {
        return new PriceResult(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(200),
                BigDecimal.valueOf(150), "INR", Instant.now(), null, status, 0,
                Instant.now(), Instant.now());
    }

    @Test
    void shouldReturnPricesByProductId() {
        var productId = UUID.randomUUID();
        var result = new PriceResult(UUID.randomUUID(), productId, BigDecimal.valueOf(200),
                BigDecimal.valueOf(150), "INR", Instant.now(), null, "ACTIVE", 0,
                Instant.now(), Instant.now());
        when(getPricesUseCase.execute(ProductId.fromUUID(productId))).thenReturn(Flux.just(result));

        StepVerifier.create(controller.getPrices(productId, null))
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

    @Test
    void batchLookupReturnsOnlyPublishedPricesForTheRequestedProducts() {
        var a = price("ACTIVE");
        var b = price("DRAFT");
        var c = price("ACTIVE");
        when(getPricesUseCase.execute(ProductId.fromUUID(a.productId()))).thenReturn(Flux.just(a));
        when(getPricesUseCase.execute(ProductId.fromUUID(b.productId()))).thenReturn(Flux.just(b));
        when(getPricesUseCase.execute(ProductId.fromUUID(c.productId()))).thenReturn(Flux.just(c));

        StepVerifier.create(controller.getPrices(null, java.util.List.of(a.productId(), b.productId(), c.productId(), a.productId())))
                .expectNext(a, c)
                .verifyComplete();
    }

    @Test
    void batchLookupAboveTheLimitIsRejected() {
        var ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(51).toList();

        StepVerifier.create(controller.getPrices(null, ids))
                .expectError(com.dsports.pricing.domain.exception.PricingDomainException.class)
                .verify();
    }
}
