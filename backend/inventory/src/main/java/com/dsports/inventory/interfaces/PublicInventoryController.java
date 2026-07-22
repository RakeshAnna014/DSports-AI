package com.dsports.inventory.interfaces;

import com.dsports.inventory.application.result.InventoryResult;
import com.dsports.inventory.application.usecase.GetInventoriesUseCase;
import com.dsports.inventory.application.usecase.GetInventoryByProductUseCase;
import com.dsports.inventory.application.usecase.GetInventoryUseCase;
import com.dsports.inventory.domain.model.InventoryId;
import com.dsports.inventory.domain.model.ProductId;
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
@RequestMapping(path = "/api/inventory", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Inventory")
public class PublicInventoryController {

    private final GetInventoryUseCase getInventoryUseCase;
    private final GetInventoriesUseCase getInventoriesUseCase;
    private final GetInventoryByProductUseCase getInventoryByProductUseCase;

    public PublicInventoryController(GetInventoryUseCase getInventoryUseCase,
                                     GetInventoriesUseCase getInventoriesUseCase,
                                     GetInventoryByProductUseCase getInventoryByProductUseCase) {
        this.getInventoryUseCase = getInventoryUseCase;
        this.getInventoriesUseCase = getInventoriesUseCase;
        this.getInventoryByProductUseCase = getInventoryByProductUseCase;
    }

    @GetMapping("/{productId}")
    @Operation(summary = "Get inventory by product", description = "Retrieve inventory records for a specific product")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Inventory records retrieved",
            content = @Content(schema = @Schema(implementation = InventoryResult.class)))
    })
    public Flux<InventoryResult> getInventoryByProduct(
            @Parameter(description = "Product ID") @PathVariable UUID productId) {
        return getInventoryByProductUseCase.execute(ProductId.fromUUID(productId));
    }

    @GetMapping
    @Operation(summary = "List all inventory", description = "Retrieve all inventory records")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Inventory records retrieved")
    })
    public Flux<InventoryResult> getAllInventory() {
        return getInventoriesUseCase.execute();
    }
}
