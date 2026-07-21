package com.dsports.pricing.interfaces;

import com.dsports.pricing.application.command.*;
import com.dsports.pricing.application.result.PriceResult;
import com.dsports.pricing.application.usecase.*;
import com.dsports.pricing.domain.model.PriceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminPriceControllerTest {

    @Mock
    private CreatePriceUseCase createPriceUseCase;

    @Mock
    private UpdatePriceUseCase updatePriceUseCase;

    @Mock
    private ActivatePriceUseCase activatePriceUseCase;

    @Mock
    private SchedulePriceUseCase schedulePriceUseCase;

    @Mock
    private ArchivePriceUseCase archivePriceUseCase;

    @Mock
    private GetPriceUseCase getPriceUseCase;

    @InjectMocks
    private AdminPriceController controller;

    @Test
    void shouldCreatePrice() {
        var result = new PriceResult(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(200),
                BigDecimal.valueOf(150), "INR", Instant.now(), null, "DRAFT", 0,
                Instant.now(), Instant.now());
        when(createPriceUseCase.execute(any())).thenReturn(Mono.just(result));

        var command = new CreatePriceCommand(UUID.randomUUID(), BigDecimal.valueOf(200),
                BigDecimal.valueOf(150), "INR", Instant.now(), null);

        StepVerifier.create(controller.createPrice(command))
                .expectNextMatches(r -> r.currency().equals("INR"))
                .verifyComplete();
    }

    @Test
    void shouldUpdatePrice() {
        var priceId = UUID.randomUUID();
        var result = new PriceResult(priceId, UUID.randomUUID(), BigDecimal.valueOf(250),
                BigDecimal.valueOf(200), "INR", Instant.now(), null, "DRAFT", 0,
                Instant.now(), Instant.now());
        when(updatePriceUseCase.execute(any())).thenReturn(Mono.just(result));

        var body = new UpdatePriceRequestBody(BigDecimal.valueOf(250), BigDecimal.valueOf(200),
                Instant.now(), null);

        StepVerifier.create(controller.updatePrice(priceId, body))
                .expectNextMatches(r -> r.id().equals(priceId))
                .verifyComplete();
    }

    @Test
    void shouldGetPrice() {
        var priceId = UUID.randomUUID();
        var result = new PriceResult(priceId, UUID.randomUUID(), BigDecimal.valueOf(200),
                BigDecimal.valueOf(150), "INR", Instant.now(), null, "DRAFT", 0,
                Instant.now(), Instant.now());
        when(getPriceUseCase.execute(PriceId.fromUUID(priceId))).thenReturn(Mono.just(result));

        StepVerifier.create(controller.getPrice(priceId))
                .expectNextMatches(r -> r.id().equals(priceId))
                .verifyComplete();
    }

    @Test
    void shouldActivatePrice() {
        var priceId = UUID.randomUUID();
        var result = new PriceResult(priceId, UUID.randomUUID(), BigDecimal.valueOf(200),
                BigDecimal.valueOf(150), "INR", Instant.now(), null, "ACTIVE", 0,
                Instant.now(), Instant.now());
        when(activatePriceUseCase.execute(any())).thenReturn(Mono.just(result));

        StepVerifier.create(controller.activatePrice(priceId))
                .expectNextMatches(r -> r.status().equals("ACTIVE"))
                .verifyComplete();
    }

    @Test
    void shouldSchedulePrice() {
        var priceId = UUID.randomUUID();
        var scheduledFrom = Instant.now().plusSeconds(86400);
        var result = new PriceResult(priceId, UUID.randomUUID(), BigDecimal.valueOf(200),
                BigDecimal.valueOf(150), "INR", scheduledFrom, null, "SCHEDULED", 0,
                Instant.now(), Instant.now());
        when(schedulePriceUseCase.execute(any())).thenReturn(Mono.just(result));

        var command = new SchedulePriceCommand(priceId, scheduledFrom);

        StepVerifier.create(controller.schedulePrice(priceId, command))
                .expectNextMatches(r -> r.status().equals("SCHEDULED"))
                .verifyComplete();
    }

    @Test
    void shouldArchivePrice() {
        var priceId = UUID.randomUUID();
        var result = new PriceResult(priceId, UUID.randomUUID(), BigDecimal.valueOf(200),
                BigDecimal.valueOf(150), "INR", Instant.now(), null, "ARCHIVED", 0,
                Instant.now(), Instant.now());
        when(archivePriceUseCase.execute(any())).thenReturn(Mono.just(result));

        StepVerifier.create(controller.archivePrice(priceId))
                .expectNextMatches(r -> r.status().equals("ARCHIVED"))
                .verifyComplete();
    }
}
