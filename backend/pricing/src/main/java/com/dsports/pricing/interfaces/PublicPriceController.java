package com.dsports.pricing.interfaces;

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

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/prices", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Pricing")
public class PublicPriceController {

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
            @RequestParam(required = false) UUID productId) {
        if (productId != null) {
            return getPricesUseCase.execute(ProductId.fromUUID(productId));
        }
        return getPricesUseCase.execute();
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
        return getPriceUseCase.execute(PriceId.fromUUID(id));
    }
}
