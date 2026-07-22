package com.dsports.cart.interfaces;

import com.dsports.cart.application.command.AddToCartCommand;
import com.dsports.cart.application.command.UpdateCartItemCommand;
import com.dsports.cart.application.result.CartResult;
import com.dsports.cart.application.usecase.AddToCartUseCase;
import com.dsports.cart.application.usecase.ClearCartUseCase;
import com.dsports.cart.application.usecase.GetCartUseCase;
import com.dsports.cart.application.usecase.RemoveCartItemUseCase;
import com.dsports.cart.application.usecase.UpdateCartItemUseCase;
import com.dsports.cart.domain.model.CartItemId;
import com.dsports.cart.domain.model.UserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/cart", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Shopping Cart")
public class PublicCartController {

    private final GetCartUseCase getCartUseCase;
    private final AddToCartUseCase addToCartUseCase;
    private final UpdateCartItemUseCase updateCartItemUseCase;
    private final RemoveCartItemUseCase removeCartItemUseCase;
    private final ClearCartUseCase clearCartUseCase;

    public PublicCartController(GetCartUseCase getCartUseCase,
                                AddToCartUseCase addToCartUseCase,
                                UpdateCartItemUseCase updateCartItemUseCase,
                                RemoveCartItemUseCase removeCartItemUseCase,
                                ClearCartUseCase clearCartUseCase) {
        this.getCartUseCase = getCartUseCase;
        this.addToCartUseCase = addToCartUseCase;
        this.updateCartItemUseCase = updateCartItemUseCase;
        this.removeCartItemUseCase = removeCartItemUseCase;
        this.clearCartUseCase = clearCartUseCase;
    }

    @GetMapping
    @Operation(summary = "Get current user's active cart",
               description = "Returns the active shopping cart for the authenticated user, or 404 if no cart exists")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Cart found",
                     content = @Content(schema = @Schema(implementation = CartResult.class))),
        @ApiResponse(responseCode = "404", description = "No active cart found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<ResponseEntity<CartResult>> getCart(Authentication authentication) {
        var userId = extractUserId(authentication);
        return getCartUseCase.execute(userId)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Add item to cart",
               description = "Adds a product to the cart. Creates a new cart if none exists. " +
                   "Validates product existence, active status, inventory, and pricing.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Item added to cart",
                     content = @Content(schema = @Schema(implementation = CartResult.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(responseCode = "404", description = "Product or price not found"),
        @ApiResponse(responseCode = "409", description = "Conflict (insufficient stock, inactive product)"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<CartResult> addItem(Authentication authentication,
                                    @Valid @RequestBody AddItemRequest request) {
        var userId = extractUserId(authentication);
        var command = new AddToCartCommand(request.productId(), request.quantity());
        return addToCartUseCase.execute(userId, command);
    }

    @PutMapping("/items/{itemId}")
    @Operation(summary = "Update cart item quantity",
               description = "Updates the quantity of a specific item in the cart. Validates inventory for increases.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Item quantity updated",
                     content = @Content(schema = @Schema(implementation = CartResult.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(responseCode = "404", description = "Cart or item not found"),
        @ApiResponse(responseCode = "409", description = "Conflict (insufficient stock)"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<CartResult> updateItemQuantity(
            Authentication authentication,
            @Parameter(description = "Cart item ID") @PathVariable UUID itemId,
            @Valid @RequestBody UpdateQuantityRequest request) {
        var userId = extractUserId(authentication);
        var command = new UpdateCartItemCommand(itemId, request.quantity());
        return updateCartItemUseCase.execute(userId, command);
    }

    @DeleteMapping("/items/{itemId}")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Remove item from cart",
               description = "Removes a specific item from the cart.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Item removed from cart",
                     content = @Content(schema = @Schema(implementation = CartResult.class))),
        @ApiResponse(responseCode = "404", description = "Cart or item not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<CartResult> removeItem(
            Authentication authentication,
            @Parameter(description = "Cart item ID") @PathVariable UUID itemId) {
        var userId = extractUserId(authentication);
        return removeCartItemUseCase.execute(userId, CartItemId.fromUUID(itemId));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Clear cart",
               description = "Removes all items from the cart.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Cart cleared",
                     content = @Content(schema = @Schema(implementation = CartResult.class))),
        @ApiResponse(responseCode = "404", description = "No active cart found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<CartResult> clearCart(Authentication authentication) {
        var userId = extractUserId(authentication);
        return clearCartUseCase.execute(userId);
    }

    private UserId extractUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        return UserId.fromString(authentication.getPrincipal().toString());
    }
}
