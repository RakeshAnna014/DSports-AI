package com.dsports.inventory.application.usecase;

import com.dsports.inventory.application.command.CreateInventoryCommand;
import com.dsports.inventory.application.port.InventoryRepository;
import com.dsports.inventory.application.result.InventoryResult;
import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import com.dsports.inventory.domain.model.*;
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
class CreateInventoryUseCaseTest {

    @Mock
    private InventoryRepository inventoryRepository;

    private CreateInventoryUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CreateInventoryUseCase(inventoryRepository);
    }

    @Test
    void shouldCreateInventorySuccessfully() {
        var productId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        var command = new CreateInventoryCommand(productId, warehouseId, 100, 10);

        when(inventoryRepository.existsByProductIdAndWarehouseId(any(), any()))
                .thenReturn(Mono.just(false));
        when(inventoryRepository.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> {
                    assertThat(result).isNotNull();
                    assertThat(result.productId()).isEqualTo(productId);
                    assertThat(result.warehouseId()).isEqualTo(warehouseId);
                    assertThat(result.availableQuantity()).isEqualTo(100);
                    assertThat(result.reorderLevel()).isEqualTo(10);
                    assertThat(result.id()).isNotNull();
                })
                .verifyComplete();

        verify(inventoryRepository).save(any());
    }

    @Test
    void shouldRejectDuplicateInventory() {
        var command = new CreateInventoryCommand(UUID.randomUUID(), UUID.randomUUID(), 100, 10);

        when(inventoryRepository.existsByProductIdAndWarehouseId(any(), any()))
                .thenReturn(Mono.just(true));

        StepVerifier.create(useCase.execute(command))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(InventoryDomainException.class);
                    assertThat(((InventoryDomainException) e).getErrorCode())
                            .isEqualTo(InventoryErrorCode.DUPLICATE_INVENTORY);
                })
                .verify();

        verify(inventoryRepository, never()).save(any());
    }

    @Test
    void shouldRejectNegativeInitialQuantity() {
        var command = new CreateInventoryCommand(UUID.randomUUID(), UUID.randomUUID(), -1, 10);

        when(inventoryRepository.existsByProductIdAndWarehouseId(any(), any()))
                .thenReturn(Mono.just(false));

        StepVerifier.create(useCase.execute(command))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(InventoryDomainException.class);
                    assertThat(((InventoryDomainException) e).getErrorCode())
                            .isEqualTo(InventoryErrorCode.INVALID_QUANTITY);
                })
                .verify();

        verify(inventoryRepository, never()).save(any());
    }
}
