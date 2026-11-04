package com.dsports.order.interfaces.checkout;

import com.dsports.order.application.promotion.usecase.CheckoutPromotionsUseCase;
import com.dsports.order.interfaces.checkout.dto.CheckoutResponse;
import com.dsports.order.interfaces.checkout.dto.PromotionDtos.ApplyPromotionRequest;
import com.dsports.order.interfaces.checkout.dto.PromotionDtos.PromotionOfferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/checkout/{checkoutId}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Checkout promotions")
public class CheckoutPromotionController {

    private final CheckoutPromotionsUseCase useCase;

    public CheckoutPromotionController(CheckoutPromotionsUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/promotions")
    @Operation(summary = "Offers for this checkout",
               description = "Currently valid promotions with this cart's authoritative discount, or the reason an offer does not apply. "
                   + "Seeded offers are synthetic demo offers (demo = true).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Offers listed"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Checkout belongs to another customer"),
        @ApiResponse(responseCode = "404", description = "Checkout not found")
    })
    public Mono<List<PromotionOfferResponse>> list(@PathVariable UUID checkoutId, Authentication authentication) {
        return useCase.list(checkoutId, customerId(authentication)).map(offers -> offers.stream().map(PromotionOfferResponse::from).toList());
    }

    @PostMapping("/promotion")
    @Operation(summary = "Apply a promotion code",
               description = "Validates the code against the checkout and applies it. Applying the same code again is idempotent; "
                   + "a different code must be removed first (one promotion per checkout). Only before the checkout is validated.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Applied; the response carries the recalculated totals"),
        @ApiResponse(responseCode = "400", description = "Invalid, expired, ineligible or below the minimum order"),
        @ApiResponse(responseCode = "404", description = "Unknown code or checkout"),
        @ApiResponse(responseCode = "409", description = "Another promotion is applied, the checkout can no longer change, or the usage limit is reached")
    })
    public Mono<CheckoutResponse> apply(@PathVariable UUID checkoutId, @Valid @RequestBody ApplyPromotionRequest request, Authentication authentication) {
        return useCase.apply(checkoutId, customerId(authentication), request.code()).map(CheckoutResponse::from);
    }

    @DeleteMapping("/promotion")
    @Operation(summary = "Remove the applied promotion", description = "Removes the promotion and recalculates the totals. Removing when none is applied is a no-op.")
    public Mono<CheckoutResponse> remove(@PathVariable UUID checkoutId, Authentication authentication) {
        return useCase.remove(checkoutId, customerId(authentication)).map(CheckoutResponse::from);
    }

    private static UUID customerId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        return UUID.fromString(authentication.getPrincipal().toString());
    }
}
