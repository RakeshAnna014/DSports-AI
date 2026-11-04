package com.dsports.wishlist.interfaces;

import com.dsports.wishlist.application.command.AddWishlistItemCommand;
import com.dsports.wishlist.application.result.WishlistResult;
import com.dsports.wishlist.application.result.WishlistSummaryResult;
import com.dsports.wishlist.application.usecase.AddWishlistItemUseCase;
import com.dsports.wishlist.application.usecase.ClearWishlistUseCase;
import com.dsports.wishlist.application.usecase.GetWishlistCountUseCase;
import com.dsports.wishlist.application.usecase.GetWishlistUseCase;
import com.dsports.wishlist.application.usecase.MoveWishlistItemToCartUseCase;
import com.dsports.wishlist.application.usecase.RemoveWishlistItemUseCase;
import com.dsports.wishlist.domain.model.UserId;
import com.dsports.wishlist.domain.model.WishlistItemId;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/wishlist", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Wishlist")
public class PublicWishlistController {

    private final GetWishlistUseCase getWishlistUseCase;
    private final AddWishlistItemUseCase addWishlistItemUseCase;
    private final RemoveWishlistItemUseCase removeWishlistItemUseCase;
    private final MoveWishlistItemToCartUseCase moveWishlistItemToCartUseCase;
    private final ClearWishlistUseCase clearWishlistUseCase;
    private final GetWishlistCountUseCase getWishlistCountUseCase;

    public PublicWishlistController(GetWishlistUseCase getWishlistUseCase,
                                    AddWishlistItemUseCase addWishlistItemUseCase,
                                    RemoveWishlistItemUseCase removeWishlistItemUseCase,
                                    MoveWishlistItemToCartUseCase moveWishlistItemToCartUseCase,
                                    ClearWishlistUseCase clearWishlistUseCase,
                                    GetWishlistCountUseCase getWishlistCountUseCase) {
        this.getWishlistUseCase = getWishlistUseCase;
        this.addWishlistItemUseCase = addWishlistItemUseCase;
        this.removeWishlistItemUseCase = removeWishlistItemUseCase;
        this.moveWishlistItemToCartUseCase = moveWishlistItemToCartUseCase;
        this.clearWishlistUseCase = clearWishlistUseCase;
        this.getWishlistCountUseCase = getWishlistCountUseCase;
    }

    @GetMapping
    @Operation(summary = "Get current user's wishlist",
               description = "Returns the wishlist for the authenticated user, or 404 if no wishlist exists")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Wishlist found",
                     content = @Content(schema = @Schema(implementation = WishlistResult.class))),
        @ApiResponse(responseCode = "404", description = "No wishlist found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<ResponseEntity<WishlistResult>> getWishlist(Authentication authentication) {
        var userId = extractUserId(authentication);
        return getWishlistUseCase.execute(userId)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/count")
    @Operation(summary = "Get wishlist item count",
               description = "Returns a lightweight summary with the total number of items in the wishlist")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Wishlist count retrieved",
                     content = @Content(schema = @Schema(implementation = WishlistSummaryResult.class))),
        @ApiResponse(responseCode = "404", description = "No wishlist found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<ResponseEntity<WishlistSummaryResult>> getWishlistCount(Authentication authentication) {
        var userId = extractUserId(authentication);
        return getWishlistCountUseCase.execute(userId)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.ok(new WishlistSummaryResult(null, 0)));
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Add item to wishlist",
               description = "Adds a product to the wishlist. Creates a new wishlist if none exists. " +
                   "Validates product existence and active status. Duplicate products are rejected.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Item added to wishlist",
                     content = @Content(schema = @Schema(implementation = WishlistResult.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(responseCode = "404", description = "Product not found"),
        @ApiResponse(responseCode = "409", description = "Conflict (duplicate item, inactive product)"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<WishlistResult> addItem(Authentication authentication,
                                        @Valid @RequestBody AddWishlistItemRequest request) {
        var userId = extractUserId(authentication);
        var command = new AddWishlistItemCommand(request.productId());
        return addWishlistItemUseCase.execute(userId, command);
    }

    @DeleteMapping("/items/{itemId}")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Remove item from wishlist",
               description = "Removes a specific item from the wishlist.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Item removed from wishlist",
                     content = @Content(schema = @Schema(implementation = WishlistResult.class))),
        @ApiResponse(responseCode = "404", description = "Wishlist or item not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<WishlistResult> removeItem(
            Authentication authentication,
            @Parameter(description = "Wishlist item ID") @PathVariable UUID itemId) {
        var userId = extractUserId(authentication);
        return removeWishlistItemUseCase.execute(userId, WishlistItemId.fromUUID(itemId));
    }

    @PostMapping("/items/{itemId}/move-to-cart")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Move wishlist item to cart",
               description = "Removes the item from the wishlist and adds it to the shopping cart with quantity 1.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Item moved to cart",
                     content = @Content(schema = @Schema(implementation = WishlistResult.class))),
        @ApiResponse(responseCode = "404", description = "Wishlist or item not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<WishlistResult> moveItemToCart(
            Authentication authentication,
            @Parameter(description = "Wishlist item ID") @PathVariable UUID itemId) {
        var userId = extractUserId(authentication);
        return moveWishlistItemToCartUseCase.execute(userId, WishlistItemId.fromUUID(itemId));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Clear wishlist",
               description = "Removes all items from the wishlist.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Wishlist cleared",
                     content = @Content(schema = @Schema(implementation = WishlistResult.class))),
        @ApiResponse(responseCode = "404", description = "No wishlist found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<WishlistResult> clearWishlist(Authentication authentication) {
        var userId = extractUserId(authentication);
        return clearWishlistUseCase.execute(userId);
    }

    private UserId extractUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        return UserId.fromString(authentication.getPrincipal().toString());
    }
}
