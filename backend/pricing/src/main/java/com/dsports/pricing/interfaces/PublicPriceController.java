package com.dsports.pricing.interfaces;

import com.dsports.pricing.domain.exception.PricingErrorCode;
import com.dsports.pricing.domain.exception.PricingDomainException;
import com.dsports.pricing.application.result.PriceResult;
import com.dsports.pricing.application.usecase.GetPriceUseCase;
import com.dsports.pricing.application.usecase.GetPricesUseCase;
import com.dsports.pricing.domain.model.PriceId;
import com.dsports.pricing.domain.model.ProductId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(path = "/api/prices", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Pricing")
public class PublicPriceController {

    private static final int MAX_BATCH = 50;

    private final GetPricesUseCase getPricesUseCase;
    private final GetPriceUseCase getPriceUseCase;

    public PublicPriceController(GetPricesUseCase getPricesUseCase,
                                  GetPriceUseCase getPriceUseCase) {
        this.getPricesUseCase = getPricesUseCase;
        this.getPriceUseCase = getPriceUseCase;
    }

    @GetMapping
    @Operation(summary = "List prices", description = "Retrieve all prices, optionally filtered by product ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Prices retrieved successfully")
    })
    public Flux<PriceResult> getPrices(
            @Parameter(description = "Filter by product ID")
            @RequestParam(required = false) UUID productId,
            @Parameter(description = "Batch lookup: up to 50 product IDs")
            @RequestParam(required = false) List<UUID> productIds) {
        Flux<PriceResult> prices;
        if (productIds != null && !productIds.isEmpty()) {
            if (productIds.size() > MAX_BATCH) {
                return Flux.error(new PricingDomainException(PricingErrorCode.VALIDATION_ERROR,
                    "At most " + MAX_BATCH + " productIds are allowed per request"));
            }
            prices = Flux.fromIterable(new LinkedHashSet<>(productIds))
                .concatMap(id -> getPricesUseCase.execute(ProductId.fromUUID(id)));
        } else if (productId != null) {
            prices = getPricesUseCase.execute(ProductId.fromUUID(productId));
        } else {
            prices = getPricesUseCase.execute();
        }
        return prices.filter(PublicPriceController::isPublished);
    }

    /** Draft, scheduled and archived prices are internal; customers only see the price currently in force. */
    private static boolean isPublished(PriceResult price) {
        return "ACTIVE".equals(price.status());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get price by ID", description = "Retrieve a single price record by its ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Price found",
            content = @Content(schema = @Schema(implementation = PriceResult.class))),
        @ApiResponse(responseCode = "404", description = "Price not found")
    })
    public Mono<PriceResult> getPrice(
            @Parameter(description = "Price ID") @PathVariable UUID id) {
        return getPriceUseCase.execute(PriceId.fromUUID(id))
            .filter(PublicPriceController::isPublished)
            .switchIfEmpty(Mono.error(new PricingDomainException(PricingErrorCode.PRICE_NOT_FOUND,
                "Price not found: " + id)));
    }
}
