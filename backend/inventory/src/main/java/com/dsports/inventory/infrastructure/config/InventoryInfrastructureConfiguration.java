package com.dsports.inventory.infrastructure.config;

import com.dsports.inventory.application.port.StockReservationRepository;
import com.dsports.inventory.application.usecase.FulfillOrderReservationsUseCase;
import com.dsports.inventory.application.usecase.ReleaseOrderReservationsUseCase;
import com.dsports.inventory.application.usecase.ReserveStockForOrderUseCase;
import com.dsports.inventory.infrastructure.persistence.repository.SpringR2dbcStockReservationRepository;
import com.dsports.inventory.infrastructure.persistence.repository.StockReservationR2dbcRepositoryAdapter;
import com.dsports.inventory.application.port.EventPublisher;
import com.dsports.inventory.application.port.InventoryRepository;
import com.dsports.inventory.application.usecase.*;
import com.dsports.inventory.infrastructure.event.InventorySpringEventPublisherAdapter;
import com.dsports.inventory.infrastructure.persistence.mapper.InventoryEntityMapper;
import com.dsports.inventory.infrastructure.persistence.repository.InventoryR2dbcRepositoryAdapter;
import com.dsports.inventory.infrastructure.persistence.repository.SpringR2dbcInventoryRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
public class InventoryInfrastructureConfiguration {

    @Bean
    public InventoryEntityMapper inventoryEntityMapper() {
        return new InventoryEntityMapper();
    }

    @Bean
    public EventPublisher inventoryEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        return new InventorySpringEventPublisherAdapter(applicationEventPublisher);
    }

    @Bean
    public TransactionalOperator inventoryTransactionalOperator(ReactiveTransactionManager reactiveTransactionManager) {
        return TransactionalOperator.create(reactiveTransactionManager);
    }

    @Bean
    public InventoryRepository inventoryRepository(
            SpringR2dbcInventoryRepository springRepository,
            InventoryEntityMapper mapper,
            EventPublisher inventoryEventPublisher,
            TransactionalOperator inventoryTransactionalOperator) {
        return new InventoryR2dbcRepositoryAdapter(springRepository, mapper,
                inventoryEventPublisher, inventoryTransactionalOperator);
    }

    @Bean
    public StockReservationRepository stockReservationRepository(
            SpringR2dbcStockReservationRepository springStockReservationRepository) {
        return new StockReservationR2dbcRepositoryAdapter(springStockReservationRepository);
    }

    // ============ USE CASES ============

    @Bean
    public ReserveStockForOrderUseCase reserveStockForOrderUseCase(InventoryRepository inventoryRepository,
                                                                   StockReservationRepository stockReservationRepository) {
        return new ReserveStockForOrderUseCase(inventoryRepository, stockReservationRepository);
    }

    @Bean
    public FulfillOrderReservationsUseCase fulfillOrderReservationsUseCase(InventoryRepository inventoryRepository,
                                                                           StockReservationRepository stockReservationRepository) {
        return new FulfillOrderReservationsUseCase(inventoryRepository, stockReservationRepository);
    }

    @Bean
    public ReleaseOrderReservationsUseCase releaseOrderReservationsUseCase(InventoryRepository inventoryRepository,
                                                                           StockReservationRepository stockReservationRepository) {
        return new ReleaseOrderReservationsUseCase(inventoryRepository, stockReservationRepository);
    }

    @Bean
    public CreateInventoryUseCase createInventoryUseCase(InventoryRepository inventoryRepository) {
        return new CreateInventoryUseCase(inventoryRepository);
    }

    @Bean
    public GetInventoryUseCase getInventoryUseCase(InventoryRepository inventoryRepository) {
        return new GetInventoryUseCase(inventoryRepository);
    }

    @Bean
    public GetInventoriesUseCase getInventoriesUseCase(InventoryRepository inventoryRepository) {
        return new GetInventoriesUseCase(inventoryRepository);
    }

    @Bean
    public GetInventoryByProductUseCase getInventoryByProductUseCase(InventoryRepository inventoryRepository) {
        return new GetInventoryByProductUseCase(inventoryRepository);
    }

    @Bean
    public StockInUseCase stockInUseCase(InventoryRepository inventoryRepository) {
        return new StockInUseCase(inventoryRepository);
    }

    @Bean
    public StockOutUseCase stockOutUseCase(InventoryRepository inventoryRepository) {
        return new StockOutUseCase(inventoryRepository);
    }

    @Bean
    public ReserveInventoryUseCase reserveInventoryUseCase(InventoryRepository inventoryRepository) {
        return new ReserveInventoryUseCase(inventoryRepository);
    }

    @Bean
    public ReleaseReservationUseCase releaseReservationUseCase(InventoryRepository inventoryRepository) {
        return new ReleaseReservationUseCase(inventoryRepository);
    }

    @Bean
    public AdjustInventoryUseCase adjustInventoryUseCase(InventoryRepository inventoryRepository) {
        return new AdjustInventoryUseCase(inventoryRepository);
    }

    @Bean
    public UpdateReorderLevelUseCase updateReorderLevelUseCase(InventoryRepository inventoryRepository) {
        return new UpdateReorderLevelUseCase(inventoryRepository);
    }
}
