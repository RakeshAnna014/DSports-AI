package com.dsports.pricing.infrastructure.config;

import com.dsports.pricing.application.port.EventPublisher;
import com.dsports.pricing.application.port.PriceRepository;
import com.dsports.pricing.application.usecase.*;
import com.dsports.pricing.infrastructure.event.PricingSpringEventPublisherAdapter;
import com.dsports.pricing.infrastructure.persistence.mapper.PriceEntityMapper;
import com.dsports.pricing.infrastructure.persistence.repository.PriceR2dbcRepositoryAdapter;
import com.dsports.pricing.infrastructure.persistence.repository.SpringR2dbcPriceRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
public class PricingInfrastructureConfiguration {

    @Bean
    public PriceEntityMapper priceEntityMapper() {
        return new PriceEntityMapper();
    }

    @Bean
    public EventPublisher pricingEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        return new PricingSpringEventPublisherAdapter(applicationEventPublisher);
    }

    @Bean
    public TransactionalOperator pricingTransactionalOperator(ReactiveTransactionManager reactiveTransactionManager) {
        return TransactionalOperator.create(reactiveTransactionManager);
    }

    @Bean
    public PriceRepository priceRepository(
            SpringR2dbcPriceRepository springRepository,
            PriceEntityMapper mapper,
            EventPublisher pricingEventPublisher,
            TransactionalOperator pricingTransactionalOperator) {
        return new PriceR2dbcRepositoryAdapter(springRepository, mapper,
                pricingEventPublisher, pricingTransactionalOperator);
    }

    // ============ USE CASES ============

    @Bean
    public CreatePriceUseCase createPriceUseCase(PriceRepository priceRepository) {
        return new CreatePriceUseCase(priceRepository);
    }

    @Bean
    public UpdatePriceUseCase updatePriceUseCase(PriceRepository priceRepository) {
        return new UpdatePriceUseCase(priceRepository);
    }

    @Bean
    public GetPriceUseCase getPriceUseCase(PriceRepository priceRepository) {
        return new GetPriceUseCase(priceRepository);
    }

    @Bean
    public GetPricesUseCase getPricesUseCase(PriceRepository priceRepository) {
        return new GetPricesUseCase(priceRepository);
    }

    @Bean
    public ActivatePriceUseCase activatePriceUseCase(PriceRepository priceRepository) {
        return new ActivatePriceUseCase(priceRepository);
    }

    @Bean
    public SchedulePriceUseCase schedulePriceUseCase(PriceRepository priceRepository) {
        return new SchedulePriceUseCase(priceRepository);
    }

    @Bean
    public ArchivePriceUseCase archivePriceUseCase(PriceRepository priceRepository) {
        return new ArchivePriceUseCase(priceRepository);
    }
}
