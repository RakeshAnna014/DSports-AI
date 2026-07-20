package com.dsports.inventory.infrastructure.config;

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

    // ============ USE CASES ============

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
