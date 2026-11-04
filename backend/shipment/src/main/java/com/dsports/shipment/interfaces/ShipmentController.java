package com.dsports.shipment.interfaces;

import com.dsports.shipment.application.command.AssignCourierCommand;
import com.dsports.shipment.application.command.CreateShipmentCommand;
import com.dsports.shipment.application.command.UpdateShipmentStatusCommand;
import com.dsports.shipment.application.query.GetShipmentQuery;
import com.dsports.shipment.application.query.TrackShipmentQuery;
import com.dsports.shipment.application.result.ShipmentResult;
import com.dsports.shipment.application.usecase.AssignCourierUseCase;
import com.dsports.shipment.application.usecase.CreateShipmentUseCase;
import com.dsports.shipment.application.usecase.GetShipmentByOrderUseCase;
import com.dsports.shipment.application.usecase.GetShipmentUseCase;
import com.dsports.shipment.application.usecase.TrackShipmentUseCase;
import com.dsports.shipment.application.usecase.UpdateShipmentStatusUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/shipments", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Shipments")
public class ShipmentController {

    private final CreateShipmentUseCase createShipmentUseCase;
    private final GetShipmentUseCase getShipmentUseCase;
    private final GetShipmentByOrderUseCase getShipmentByOrderUseCase;
    private final TrackShipmentUseCase trackShipmentUseCase;
    private final AssignCourierUseCase assignCourierUseCase;
    private final UpdateShipmentStatusUseCase updateShipmentStatusUseCase;

    public ShipmentController(CreateShipmentUseCase createShipmentUseCase,
                               GetShipmentUseCase getShipmentUseCase,
                               GetShipmentByOrderUseCase getShipmentByOrderUseCase,
                               TrackShipmentUseCase trackShipmentUseCase,
                               AssignCourierUseCase assignCourierUseCase,
                               UpdateShipmentStatusUseCase updateShipmentStatusUseCase) {
        this.createShipmentUseCase = createShipmentUseCase;
        this.getShipmentUseCase = getShipmentUseCase;
        this.getShipmentByOrderUseCase = getShipmentByOrderUseCase;
        this.trackShipmentUseCase = trackShipmentUseCase;
        this.assignCourierUseCase = assignCourierUseCase;
        this.updateShipmentStatusUseCase = updateShipmentStatusUseCase;
    }

    @Operation(summary = "Create the shipment for a paid order (admin)",
               description = "Shipments are normally created automatically after payment capture. The delivery "
                   + "address is always the order's address snapshot. Idempotent: returns the existing shipment.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Shipment created or already existing",
                     content = @Content(schema = @Schema(implementation = ShipmentResult.class))),
        @ApiResponse(responseCode = "400", description = "Order not paid"),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires admin role"),
        @ApiResponse(responseCode = "404", description = "Order not found")
    })
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ShipmentResult> createShipment(@Valid @RequestBody CreateShipmentRequest request) {
        return createShipmentUseCase.execute(new CreateShipmentCommand(request.orderId()));
    }

    @Operation(summary = "Shipment and tracking history of one of the caller's orders",
               description = "Owner (or admin) only. 404 SHIPMENT_NOT_FOUND until the shipment has been created.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Shipment with tracking events"),
        @ApiResponse(responseCode = "403", description = "Order's shipment belongs to another user"),
        @ApiResponse(responseCode = "404", description = "No shipment for this order yet")
    })
    @GetMapping("/by-order/{orderId}")
    public Mono<GetShipmentByOrderUseCase.ShipmentTrackingResult> getShipmentByOrder(
            Authentication authentication,
            @Parameter(description = "Order ID") @PathVariable UUID orderId) {
        return getShipmentByOrderUseCase.execute(orderId, extractUserId(authentication), isAdmin(authentication));
    }

    @Operation(summary = "Get shipment by ID",
               description = "Returns a specific shipment. Users can only access their own shipments.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Shipment found",
                     content = @Content(schema = @Schema(implementation = ShipmentResult.class))),
        @ApiResponse(responseCode = "404", description = "Shipment not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @GetMapping("/{id}")
    public Mono<ResponseEntity<ShipmentResult>> getShipment(
            Authentication authentication,
            @Parameter(description = "Shipment ID") @PathVariable UUID id) {
        var userId = extractUserId(authentication);
        var isAdmin = isAdmin(authentication);
        var query = new GetShipmentQuery(id, userId, isAdmin);
        return getShipmentUseCase.execute(query)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Track shipment by tracking number",
               description = "Returns shipment details for a tracking number. Only the shipment's owner or an admin.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Shipment found",
                     content = @Content(schema = @Schema(implementation = ShipmentResult.class))),
        @ApiResponse(responseCode = "404", description = "Tracking number not found")
    })
    @GetMapping("/track/{trackingNumber}")
    public Mono<ResponseEntity<ShipmentResult>> trackShipment(
            Authentication authentication,
            @Parameter(description = "Tracking number") @PathVariable String trackingNumber) {
        var query = new TrackShipmentQuery(trackingNumber, extractUserId(authentication), isAdmin(authentication));
        return trackShipmentUseCase.execute(query)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Assign courier to shipment",
               description = "Assigns a courier provider to a shipment. Admin only.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Courier assigned",
                     content = @Content(schema = @Schema(implementation = ShipmentResult.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires admin role"),
        @ApiResponse(responseCode = "404", description = "Shipment not found")
    })
    @PostMapping("/{id}/courier")
    public Mono<ShipmentResult> assignCourier(
            Authentication authentication,
            @Parameter(description = "Shipment ID") @PathVariable UUID id,
            @Valid @RequestBody AssignCourierRequest request) {
        if (!isAdmin(authentication)) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required"));
        }
        return assignCourierUseCase.execute(new AssignCourierCommand(id, request.courierProvider()));
    }

    @Operation(summary = "Update shipment status",
               description = "Updates the shipment status. Admin only.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Status updated",
                     content = @Content(schema = @Schema(implementation = ShipmentResult.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires admin role"),
        @ApiResponse(responseCode = "404", description = "Shipment not found")
    })
    @PutMapping("/admin/{id}/status")
    public Mono<ShipmentResult> updateStatus(
            Authentication authentication,
            @Parameter(description = "Shipment ID") @PathVariable UUID id,
            @Valid @RequestBody UpdateStatusRequest request) {
        if (!isAdmin(authentication)) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required"));
        }
        return updateShipmentStatusUseCase.execute(
                new UpdateShipmentStatusCommand(id, request.newStatus(), request.reason()));
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        return UUID.fromString(authentication.getPrincipal().toString());
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    // Request DTOs

    public record CreateShipmentRequest(@NotNull UUID orderId) {}

    public record AssignCourierRequest(
            @NotNull com.dsports.shipment.domain.model.CourierProvider courierProvider
    ) {}

    public record UpdateStatusRequest(
            @NotNull com.dsports.shipment.domain.model.ShipmentStatus newStatus,
            @Size(max = 500) String reason
    ) {}
}
