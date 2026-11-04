package com.dsports.inventory.interfaces;

import com.dsports.inventory.application.result.InventoryResult;
import com.dsports.inventory.application.usecase.GetInventoriesUseCase;
import com.dsports.inventory.application.usecase.GetInventoryByProductUseCase;
import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import com.dsports.inventory.domain.model.InventoryStatus;
import com.dsports.inventory.domain.model.ProductId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Storefront stock view: one summary per product with the quantity that can still be bought. Warehouse identifiers,
 * reservations and reorder levels are internal and only available through the admin API.
 */
@RestController
@RequestMapping(path = "/api/inventory", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Inventory")
public class PublicInventoryController {

    private static final int MAX_BATCH = 50;

    private final GetInventoriesUseCase getInventoriesUseCase;
    private final GetInventoryByProductUseCase getInventoryByProductUseCase;

    public PublicInventoryController(GetInventoriesUseCase getInventoriesUseCase,
                                     GetInventoryByProductUseCase getInventoryByProductUseCase) {
        this.getInventoriesUseCase = getInventoriesUseCase;
        this.getInventoryByProductUseCase = getInventoryByProductUseCase;
    }

    @Schema(description = "Stock available to customers for a product")
    public record ProductStock(
        UUID productId,
        @Schema(description = "Units that can be ordered now (on hand minus reserved for unpaid orders)") int availableQuantity,
        @Schema(description = "IN_STOCK, LOW_STOCK or OUT_OF_STOCK") String status
    ) {}

    @GetMapping("/{productId}")
    @Operation(summary = "Stock available for a product (empty list when the product is not stocked)")
    public Flux<ProductStock> getInventoryByProduct(@Parameter(description = "Product ID") @PathVariable UUID productId) {
        return getInventoryByProductUseCase.execute(ProductId.fromUUID(productId))
            .collectList()
            .flatMapMany(rows -> rows.isEmpty() ? Flux.empty() : Flux.just(summarize(productId, rows)));
    }

    @GetMapping
    @Operation(summary = "Stock available for every stocked product, or for up to 50 products (?productIds=a,b,c)")
    public Flux<ProductStock> getAllInventory(
            @Parameter(description = "Batch lookup: up to 50 product IDs") @RequestParam(required = false) List<UUID> productIds) {
        if (productIds != null && !productIds.isEmpty()) {
            if (productIds.size() > MAX_BATCH) {
                return Flux.error(new InventoryDomainException(InventoryErrorCode.VALIDATION_ERROR,
                    "At most " + MAX_BATCH + " productIds are allowed per request"));
            }
            return Flux.fromIterable(new LinkedHashSet<>(productIds))
                .concatMap(id -> getInventoryByProduct(id));
        }
        return getInventoriesUseCase.execute()
            .groupBy(InventoryResult::productId)
            .flatMap(group -> group.collectList().map(rows -> summarize(group.key(), rows)));
    }

    private static ProductStock summarize(UUID productId, List<InventoryResult> rows) {
        var sellable = rows.stream().mapToInt(r -> Math.max(0, r.availableQuantity() - r.reservedQuantity())).sum();
        var reorderLevel = rows.stream().mapToInt(InventoryResult::reorderLevel).sum();
        return new ProductStock(productId, sellable, InventoryStatus.fromAvailability(sellable, reorderLevel).name());
    }
}
