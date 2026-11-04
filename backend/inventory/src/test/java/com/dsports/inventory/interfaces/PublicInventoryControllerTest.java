package com.dsports.inventory.interfaces;

import com.dsports.inventory.application.result.InventoryResult;
import com.dsports.inventory.application.usecase.GetInventoriesUseCase;
import com.dsports.inventory.application.usecase.GetInventoryByProductUseCase;
import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.model.ProductId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicInventoryControllerTest {

    private final GetInventoriesUseCase getAll = mock(GetInventoriesUseCase.class);
    private final GetInventoryByProductUseCase getByProduct = mock(GetInventoryByProductUseCase.class);
    private PublicInventoryController controller;

    @BeforeEach
    void setUp() {
        controller = new PublicInventoryController(getAll, getByProduct);
    }

    @Test
    void summaryShowsSellableQuantityAndHidesInternalFields() {
        var product = UUID.randomUUID();
        when(getByProduct.execute(ProductId.fromUUID(product))).thenReturn(Flux.just(
            row(product, 10, 3, 2), row(product, 5, 0, 2)));

        StepVerifier.create(controller.getInventoryByProduct(product))
            .expectNextMatches(s -> s.availableQuantity() == 12 && s.status().equals("IN_STOCK"))
            .verifyComplete();
    }

    @Test
    void batchLookupSummarisesEachRequestedProductOnce() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        when(getByProduct.execute(ProductId.fromUUID(a))).thenReturn(Flux.just(row(a, 4, 4, 1)));
        when(getByProduct.execute(ProductId.fromUUID(b))).thenReturn(Flux.just(row(b, 9, 0, 1)));

        StepVerifier.create(controller.getAllInventory(List.of(a, b, a)))
            .expectNextMatches(s -> s.productId().equals(a) && s.availableQuantity() == 0 && s.status().equals("OUT_OF_STOCK"))
            .expectNextMatches(s -> s.productId().equals(b) && s.availableQuantity() == 9)
            .verifyComplete();
    }

    @Test
    void batchLookupAboveTheLimitIsRejected() {
        var ids = Stream.generate(UUID::randomUUID).limit(51).toList();

        StepVerifier.create(controller.getAllInventory(ids))
            .expectError(InventoryDomainException.class)
            .verify();
    }

    private static InventoryResult row(UUID product, int onHand, int reserved, int reorder) {
        return new InventoryResult(UUID.randomUUID(), product, UUID.randomUUID(), onHand, reserved, reorder,
            "IN_STOCK", 1, Instant.now(), Instant.now());
    }
}
