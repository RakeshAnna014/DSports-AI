package com.dsports.inventory.interfaces;

import com.dsports.inventory.application.command.*;
import com.dsports.inventory.application.result.InventoryResult;
import com.dsports.inventory.application.usecase.*;
import com.dsports.inventory.domain.model.InventoryId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/admin/inventory", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin")
@SecurityRequirement(name = "bearer-jwt")
public class AdminInventoryController {

    private final CreateInventoryUseCase createInventoryUseCase;
    private final StockInUseCase stockInUseCase;
    private final StockOutUseCase stockOutUseCase;
    private final ReserveInventoryUseCase reserveInventoryUseCase;
    private final ReleaseReservationUseCase releaseReservationUseCase;
    private final AdjustInventoryUseCase adjustInventoryUseCase;
    private final UpdateReorderLevelUseCase updateReorderLevelUseCase;
    private final GetInventoryUseCase getInventoryUseCase;

    public AdminInventoryController(CreateInventoryUseCase createInventoryUseCase,
                                     StockInUseCase stockInUseCase,
                                     StockOutUseCase stockOutUseCase,
                                     ReserveInventoryUseCase reserveInventoryUseCase,
                                     ReleaseReservationUseCase releaseReservationUseCase,
                                     AdjustInventoryUseCase adjustInventoryUseCase,
                                     UpdateReorderLevelUseCase updateReorderLevelUseCase,
                                     GetInventoryUseCase getInventoryUseCase) {
        this.createInventoryUseCase = createInventoryUseCase;
        this.stockInUseCase = stockInUseCase;
        this.stockOutUseCase = stockOutUseCase;
        this.reserveInventoryUseCase = reserveInventoryUseCase;
        this.releaseReservationUseCase = releaseReservationUseCase;
        this.adjustInventoryUseCase = adjustInventoryUseCase;
        this.updateReorderLevelUseCase = updateReorderLevelUseCase;
        this.getInventoryUseCase = getInventoryUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create inventory record", description = "Create a new inventory record (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Inventory created",
            content = @Content(schema = @Schema(implementation = InventoryResult.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "409", description = "Inventory record already exists")
    })
    public Mono<InventoryResult> createInventory(@Valid @RequestBody CreateInventoryCommand command) {
        return createInventoryUseCase.execute(command);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get inventory by ID", description = "Retrieve a single inventory record (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Inventory found",
            content = @Content(schema = @Schema(implementation = InventoryResult.class))),
        @ApiResponse(responseCode = "404", description = "Inventory not found")
    })
    public Mono<InventoryResult> getInventory(@Parameter(description = "Inventory ID") @PathVariable UUID id) {
        return getInventoryUseCase.execute(InventoryId.fromUUID(id));
    }

    @PatchMapping("/stock-in")
    @Operation(summary = "Stock in", description = "Increase inventory stock quantity (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Stock updated",
            content = @Content(schema = @Schema(implementation = InventoryResult.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Inventory not found")
    })
    public Mono<InventoryResult> stockIn(@Valid @RequestBody StockInCommand command) {
        return stockInUseCase.execute(command);
    }

    @PatchMapping("/stock-out")
    @Operation(summary = "Stock out", description = "Decrease inventory stock quantity (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Stock updated",
            content = @Content(schema = @Schema(implementation = InventoryResult.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Inventory not found")
    })
    public Mono<InventoryResult> stockOut(@Valid @RequestBody StockOutCommand command) {
        return stockOutUseCase.execute(command);
    }

    @PatchMapping("/reserve")
    @Operation(summary = "Reserve inventory", description = "Reserve stock for an order (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Inventory reserved",
            content = @Content(schema = @Schema(implementation = InventoryResult.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Inventory not found")
    })
    public Mono<InventoryResult> reserve(@Valid @RequestBody ReserveInventoryCommand command) {
        return reserveInventoryUseCase.execute(command);
    }

    @PatchMapping("/release")
    @Operation(summary = "Release reservation", description = "Release reserved stock back to available (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Reservation released",
            content = @Content(schema = @Schema(implementation = InventoryResult.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Inventory not found")
    })
    public Mono<InventoryResult> release(@Valid @RequestBody ReleaseReservationCommand command) {
        return releaseReservationUseCase.execute(command);
    }

    @PatchMapping("/adjust")
    @Operation(summary = "Adjust inventory", description = "Adjust inventory quantity (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Inventory adjusted",
            content = @Content(schema = @Schema(implementation = InventoryResult.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Inventory not found")
    })
    public Mono<InventoryResult> adjust(@Valid @RequestBody AdjustInventoryCommand command) {
        return adjustInventoryUseCase.execute(command);
    }

    @PatchMapping("/reorder-level")
    @Operation(summary = "Update reorder level", description = "Update the reorder threshold for an inventory record (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Reorder level updated",
            content = @Content(schema = @Schema(implementation = InventoryResult.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Inventory not found")
    })
    public Mono<InventoryResult> updateReorderLevel(@Valid @RequestBody UpdateReorderLevelCommand command) {
        return updateReorderLevelUseCase.execute(command);
    }
}
