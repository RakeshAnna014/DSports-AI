package com.dsports.config;

import com.dsports.identity.application.usecase.GetAddressesUseCase;
import com.dsports.inventory.application.usecase.GetInventoryByProductUseCase;
import com.dsports.order.application.checkout.port.AddressPort;
import com.dsports.order.application.checkout.port.CartPort;
import com.dsports.order.application.checkout.port.InventoryPort;
import com.dsports.order.application.checkout.port.PricingPort;
import com.dsports.pricing.application.usecase.GetPricesUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Configuration
public class CheckoutModuleConfig {

    private final com.dsports.cart.application.usecase.GetCartUseCase getCartUseCase;
    private final GetInventoryByProductUseCase getInventoryByProductUseCase;
    private final GetPricesUseCase getPricesUseCase;
    private final GetAddressesUseCase getAddressesUseCase;

    public CheckoutModuleConfig(com.dsports.cart.application.usecase.GetCartUseCase getCartUseCase,
                                 GetInventoryByProductUseCase getInventoryByProductUseCase,
                                 GetPricesUseCase getPricesUseCase,
                                 GetAddressesUseCase getAddressesUseCase) {
        this.getCartUseCase = getCartUseCase;
        this.getInventoryByProductUseCase = getInventoryByProductUseCase;
        this.getPricesUseCase = getPricesUseCase;
        this.getAddressesUseCase = getAddressesUseCase;
    }

    @Bean
    public CartPort checkoutCartPort() {
        return customerId -> getCartUseCase.execute(
                com.dsports.cart.domain.model.UserId.fromUUID(customerId))
            .map(result -> new CartPort.CartData(
                result.id(),
                result.items().stream()
                    .map(item -> new CartPort.CartItemData(
                        item.productId(), item.productName(), null,
                        item.quantity(), item.unitPrice(), null,
                        null
                    ))
                    .toList()
            ));
    }

    @Bean
    public InventoryPort checkoutInventoryPort() {
        return (productId, requestedQuantity) ->
            getInventoryByProductUseCase.execute(
                    com.dsports.inventory.domain.model.ProductId.fromUUID(productId))
                .reduce(0, (sum, item) -> sum + item.availableQuantity())
                .map(availableQty -> new InventoryPort.InventoryResult(
                    productId, availableQty, availableQty >= requestedQuantity))
                .switchIfEmpty(Mono.just(new InventoryPort.InventoryResult(
                    productId, 0, false)));
    }

    @Bean
    public PricingPort checkoutPricingPort() {
        return productId ->
            getPricesUseCase.execute(
                    com.dsports.pricing.domain.model.ProductId.fromUUID(productId))
                .filter(price -> "ACTIVE".equals(price.status()))
                .next()
                .map(price -> new PricingPort.PriceResult(
                    price.productId(), price.sellingPrice(), price.currency()));
    }

    @Bean
    public AddressPort checkoutAddressPort() {
        return (addressId, customerId) ->
            getAddressesUseCase.execute(
                    com.dsports.identity.domain.model.UserId.fromUUID(customerId))
                .map(addresses -> addresses.addresses().stream()
                    .anyMatch(addr -> addr.addressId().equals(addressId)))
                .defaultIfEmpty(false);
    }
}
