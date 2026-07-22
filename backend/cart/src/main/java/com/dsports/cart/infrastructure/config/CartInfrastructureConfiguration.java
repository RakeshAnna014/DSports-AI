package com.dsports.cart.infrastructure.config;

import com.dsports.cart.application.port.CartRepository;
import com.dsports.cart.application.port.EventPublisher;
import com.dsports.cart.application.port.InventoryPort;
import com.dsports.cart.application.port.PricingPort;
import com.dsports.cart.application.port.ProductCatalogPort;
import com.dsports.cart.application.usecase.AddToCartUseCase;
import com.dsports.cart.application.usecase.ClearCartUseCase;
import com.dsports.cart.application.usecase.CreateCartUseCase;
import com.dsports.cart.application.usecase.GetCartUseCase;
import com.dsports.cart.application.usecase.RemoveCartItemUseCase;
import com.dsports.cart.application.usecase.UpdateCartItemUseCase;
import com.dsports.cart.infrastructure.event.CartSpringEventPublisherAdapter;
import com.dsports.cart.infrastructure.persistence.repository.CartR2dbcRepositoryAdapter;
import com.dsports.cart.infrastructure.persistence.repository.SpringR2dbcCartRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.transaction.ReactiveTransactionManager;

@Configuration
public class CartInfrastructureConfiguration {

    private final SpringR2dbcCartRepository springRepository;
    private final ReactiveTransactionManager transactionManager;
    private final R2dbcEntityTemplate entityTemplate;
    private final ProductCatalogPort productCatalogPort;
    private final InventoryPort inventoryPort;
    private final PricingPort pricingPort;

    public CartInfrastructureConfiguration(SpringR2dbcCartRepository springRepository,
                                           ReactiveTransactionManager transactionManager,
                                           R2dbcEntityTemplate entityTemplate,
                                           ProductCatalogPort productCatalogPort,
                                           InventoryPort inventoryPort,
                                           PricingPort pricingPort) {
        this.springRepository = springRepository;
        this.transactionManager = transactionManager;
        this.entityTemplate = entityTemplate;
        this.productCatalogPort = productCatalogPort;
        this.inventoryPort = inventoryPort;
        this.pricingPort = pricingPort;
    }

    @Bean
    public EventPublisher cartEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        return new CartSpringEventPublisherAdapter(applicationEventPublisher);
    }

    @Bean
    public CartRepository cartRepository(EventPublisher cartEventPublisher) {
        return new CartR2dbcRepositoryAdapter(springRepository, cartEventPublisher,
            transactionManager, entityTemplate);
    }

    @Bean
    public CreateCartUseCase createCartUseCase(CartRepository cartRepository) {
        return new CreateCartUseCase(cartRepository);
    }

    @Bean
    public AddToCartUseCase addToCartUseCase(CartRepository cartRepository) {
        return new AddToCartUseCase(cartRepository, productCatalogPort, inventoryPort, pricingPort);
    }

    @Bean
    public UpdateCartItemUseCase updateCartItemUseCase(CartRepository cartRepository) {
        return new UpdateCartItemUseCase(cartRepository, inventoryPort);
    }

    @Bean
    public RemoveCartItemUseCase removeCartItemUseCase(CartRepository cartRepository) {
        return new RemoveCartItemUseCase(cartRepository);
    }

    @Bean
    public ClearCartUseCase clearCartUseCase(CartRepository cartRepository) {
        return new ClearCartUseCase(cartRepository);
    }

    @Bean
    public GetCartUseCase getCartUseCase(CartRepository cartRepository) {
        return new GetCartUseCase(cartRepository);
    }
}
