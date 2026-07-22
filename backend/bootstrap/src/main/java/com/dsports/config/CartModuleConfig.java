package com.dsports.config;

import com.dsports.cart.application.port.InventoryPort;
import com.dsports.cart.application.port.PricingPort;
import com.dsports.cart.application.port.ProductCatalogPort;
import com.dsports.catalog.application.usecase.GetProductUseCase;
import com.dsports.inventory.application.usecase.GetInventoryByProductUseCase;
import com.dsports.pricing.application.usecase.GetPricesUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Configuration
public class CartModuleConfig {

    private final GetProductUseCase getProductUseCase;
    private final GetInventoryByProductUseCase getInventoryByProductUseCase;
    private final GetPricesUseCase getPricesUseCase;

    public CartModuleConfig(GetProductUseCase getProductUseCase,
                            GetInventoryByProductUseCase getInventoryByProductUseCase,
                            GetPricesUseCase getPricesUseCase) {
        this.getProductUseCase = getProductUseCase;
        this.getInventoryByProductUseCase = getInventoryByProductUseCase;
        this.getPricesUseCase = getPricesUseCase;
    }

    @Bean
    public ProductCatalogPort productCatalogPort() {
        return productId -> getProductUseCase.execute(
                com.dsports.catalog.domain.model.ProductId.fromUUID(productId))
            .map(result -> new ProductCatalogPort.ProductValidationResult(
                result.id(), result.name(), "ACTIVE".equals(result.status())))
            .switchIfEmpty(Mono.empty());
    }

    @Bean
    public InventoryPort inventoryPort() {
        return (productId, requestedQuantity) ->
            getInventoryByProductUseCase.execute(
                    com.dsports.inventory.domain.model.ProductId.fromUUID(productId))
                .reduce(0, (sum, item) -> sum + item.availableQuantity())
                .map(availableQty -> new InventoryPort.InventoryCheckResult(
                    productId, availableQty, availableQty >= requestedQuantity))
                .switchIfEmpty(Mono.just(new InventoryPort.InventoryCheckResult(
                    productId, 0, false)));
    }

    @Bean
    public PricingPort pricingPort() {
        return productId ->
            getPricesUseCase.execute(
                    com.dsports.pricing.domain.model.ProductId.fromUUID(productId))
                .filter(price -> "ACTIVE".equals(price.status()))
                .next()
                .map(price -> new PricingPort.ActivePriceResult(
                    price.productId(), price.sellingPrice(), price.currency()));
    }
}
