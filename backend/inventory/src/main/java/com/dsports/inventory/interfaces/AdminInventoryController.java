package com.dsports.inventory.interfaces;

import com.dsports.inventory.application.command.*;
import com.dsports.inventory.application.result.InventoryResult;
import com.dsports.inventory.application.usecase.*;
import com.dsports.inventory.domain.model.InventoryId;
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
    public Mono<InventoryResult> createInventory(@Valid @RequestBody CreateInventoryCommand command) {
        return createInventoryUseCase.execute(command);
    }

    @GetMapping("/{id}")
    public Mono<InventoryResult> getInventory(@PathVariable UUID id) {
        return getInventoryUseCase.execute(InventoryId.fromUUID(id));
    }

    @PatchMapping("/stock-in")
    public Mono<InventoryResult> stockIn(@Valid @RequestBody StockInCommand command) {
        return stockInUseCase.execute(command);
    }

    @PatchMapping("/stock-out")
    public Mono<InventoryResult> stockOut(@Valid @RequestBody StockOutCommand command) {
        return stockOutUseCase.execute(command);
    }

    @PatchMapping("/reserve")
    public Mono<InventoryResult> reserve(@Valid @RequestBody ReserveInventoryCommand command) {
        return reserveInventoryUseCase.execute(command);
    }

    @PatchMapping("/release")
    public Mono<InventoryResult> release(@Valid @RequestBody ReleaseReservationCommand command) {
        return releaseReservationUseCase.execute(command);
    }

    @PatchMapping("/adjust")
    public Mono<InventoryResult> adjust(@Valid @RequestBody AdjustInventoryCommand command) {
        return adjustInventoryUseCase.execute(command);
    }

    @PatchMapping("/reorder-level")
    public Mono<InventoryResult> updateReorderLevel(@Valid @RequestBody UpdateReorderLevelCommand command) {
        return updateReorderLevelUseCase.execute(command);
    }
}
