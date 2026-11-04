package com.dsports.cart.application.usecase;

import com.dsports.cart.application.command.AddToCartCommand;
import com.dsports.cart.application.port.CartRepository;
import com.dsports.cart.application.port.InventoryPort;
import com.dsports.cart.application.port.PricingPort;
import com.dsports.cart.application.port.ProductCatalogPort;
import com.dsports.cart.domain.exception.CartDomainException;
import com.dsports.cart.domain.exception.CartErrorCode;
import com.dsports.cart.domain.model.Cart;
import com.dsports.cart.domain.model.CartId;
import com.dsports.cart.domain.model.CartItemId;
import com.dsports.cart.domain.model.Money;
import com.dsports.cart.domain.model.Quantity;
import com.dsports.cart.domain.model.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AddToCartUseCaseTest {

    private final UUID productId = UUID.randomUUID();
    private final UserId userId = UserId.fromUUID(UUID.randomUUID());
    private CartRepository cartRepository;
    private InventoryPort inventoryPort;
    private AddToCartUseCase useCase;
    private Cart cart;

    @BeforeEach
    void setUp() {
        cartRepository = mock(CartRepository.class);
        inventoryPort = mock(InventoryPort.class);
        var catalog = mock(ProductCatalogPort.class);
        var pricing = mock(PricingPort.class);
        useCase = new AddToCartUseCase(cartRepository, catalog, inventoryPort, pricing);

        cart = Cart.create(CartId.generate(), userId);
        cart.addItem(CartItemId.generate(), productId.toString(), "Bat", Money.from(new BigDecimal("100.00")), Quantity.from(3));

        when(catalog.findActiveProduct(productId)).thenReturn(Mono.just(new ProductCatalogPort.ProductValidationResult(productId, "Bat", true)));
        when(pricing.getActivePrice(productId)).thenReturn(Mono.just(new PricingPort.ActivePriceResult(productId, new BigDecimal("100.00"), "INR")));
        when(cartRepository.findByUserId(userId)).thenReturn(Mono.just(cart));
        when(cartRepository.save(any())).thenReturn(Mono.empty());
        // 4 units can be sold.
        when(inventoryPort.checkAvailability(eq(productId), anyInt())).thenAnswer(invocation -> {
            int requested = invocation.getArgument(1);
            return Mono.just(new InventoryPort.InventoryCheckResult(productId, 4, requested <= 4));
        });
    }

    @Test
    void checksStockForTheQuantityTheCartWillHoldNotJustTheIncrement() {
        StepVerifier.create(useCase.execute(userId, new AddToCartCommand(productId, 2)))
            .expectErrorMatches(e -> e instanceof CartDomainException cde && cde.getErrorCode() == CartErrorCode.INSUFFICIENT_STOCK)
            .verify();

        verify(inventoryPort).checkAvailability(productId, 5);
        verify(cartRepository, never()).save(any());
    }

    @Test
    void addsWhenTheTotalFitsInStock() {
        StepVerifier.create(useCase.execute(userId, new AddToCartCommand(productId, 1)))
            .expectNextMatches(result -> result.totalItems() == 4)
            .verifyComplete();

        verify(inventoryPort).checkAvailability(productId, 4);
    }
}
