package com.dsports.pricing.interfaces;

import com.dsports.pricing.application.command.*;
import com.dsports.pricing.application.result.PriceResult;
import com.dsports.pricing.application.usecase.*;
import com.dsports.pricing.domain.model.PriceId;
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
@RequestMapping(path = "/api/admin/prices", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin")
@SecurityRequirement(name = "bearer-jwt")
public class AdminPriceController {

    private final CreatePriceUseCase createPriceUseCase;
    private final UpdatePriceUseCase updatePriceUseCase;
    private final ActivatePriceUseCase activatePriceUseCase;
    private final SchedulePriceUseCase schedulePriceUseCase;
    private final ArchivePriceUseCase archivePriceUseCase;
    private final GetPriceUseCase getPriceUseCase;

    public AdminPriceController(CreatePriceUseCase createPriceUseCase,
                                 UpdatePriceUseCase updatePriceUseCase,
                                 ActivatePriceUseCase activatePriceUseCase,
                                 SchedulePriceUseCase schedulePriceUseCase,
                                 ArchivePriceUseCase archivePriceUseCase,
                                 GetPriceUseCase getPriceUseCase) {
        this.createPriceUseCase = createPriceUseCase;
        this.updatePriceUseCase = updatePriceUseCase;
        this.activatePriceUseCase = activatePriceUseCase;
        this.schedulePriceUseCase = schedulePriceUseCase;
        this.archivePriceUseCase = archivePriceUseCase;
        this.getPriceUseCase = getPriceUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create price", description = "Create a new price record (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Price created",
            content = @Content(schema = @Schema(implementation = PriceResult.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "409", description = "Duplicate price")
    })
    public Mono<PriceResult> createPrice(@Valid @RequestBody CreatePriceCommand command) {
        return createPriceUseCase.execute(command);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update price", description = "Update an existing price record (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Price updated",
            content = @Content(schema = @Schema(implementation = PriceResult.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Price not found")
    })
    public Mono<PriceResult> updatePrice(@Parameter(description = "Price ID") @PathVariable UUID id,
                                          @Valid @RequestBody UpdatePriceRequestBody body) {
        var command = new UpdatePriceCommand(id, body.mrp(), body.sellingPrice(),
                body.effectiveFrom(), body.effectiveTo());
        return updatePriceUseCase.execute(command);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get price by ID", description = "Retrieve a single price record by ID (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Price found",
            content = @Content(schema = @Schema(implementation = PriceResult.class))),
        @ApiResponse(responseCode = "404", description = "Price not found")
    })
    public Mono<PriceResult> getPrice(@Parameter(description = "Price ID") @PathVariable UUID id) {
        return getPriceUseCase.execute(PriceId.fromUUID(id));
    }

    @PostMapping("/{id}/activate")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Activate price", description = "Activate a price record (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Price activated",
            content = @Content(schema = @Schema(implementation = PriceResult.class))),
        @ApiResponse(responseCode = "404", description = "Price not found"),
        @ApiResponse(responseCode = "409", description = "Invalid state transition")
    })
    public Mono<PriceResult> activatePrice(@Parameter(description = "Price ID") @PathVariable UUID id) {
        return activatePriceUseCase.execute(new ActivatePriceCommand(id));
    }

    @PostMapping("/{id}/schedule")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Schedule price", description = "Schedule a price to become active at a future date (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Price scheduled",
            content = @Content(schema = @Schema(implementation = PriceResult.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Price not found")
    })
    public Mono<PriceResult> schedulePrice(@Parameter(description = "Price ID") @PathVariable UUID id,
                                            @Valid @RequestBody SchedulePriceCommand command) {
        return schedulePriceUseCase.execute(command);
    }

    @PostMapping("/{id}/archive")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Archive price", description = "Archive a price record (Admin only)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Price archived",
            content = @Content(schema = @Schema(implementation = PriceResult.class))),
        @ApiResponse(responseCode = "404", description = "Price not found"),
        @ApiResponse(responseCode = "409", description = "Invalid state transition")
    })
    public Mono<PriceResult> archivePrice(@Parameter(description = "Price ID") @PathVariable UUID id) {
        return archivePriceUseCase.execute(new ArchivePriceCommand(id));
    }
}
