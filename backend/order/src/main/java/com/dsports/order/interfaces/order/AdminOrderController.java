package com.dsports.order.interfaces.order;

import com.dsports.order.application.order.command.UpdateOrderStatusCommand;
import com.dsports.order.application.order.usecase.GetOrderUseCase;
import com.dsports.order.application.order.usecase.UpdateOrderStatusUseCase;
import com.dsports.order.interfaces.order.dto.OrderResponse;
import com.dsports.order.interfaces.order.dto.UpdateOrderStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/admin/orders", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Admin")
public class AdminOrderController {

    private final UpdateOrderStatusUseCase updateOrderStatusUseCase;
    private final GetOrderUseCase getOrderUseCase;

    public AdminOrderController(UpdateOrderStatusUseCase updateOrderStatusUseCase,
                                 GetOrderUseCase getOrderUseCase) {
        this.updateOrderStatusUseCase = updateOrderStatusUseCase;
        this.getOrderUseCase = getOrderUseCase;
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get order details (Admin)",
               description = "Returns the full order details. Accessible only to ADMIN users.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Order found",
                     content = @Content(schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden - ADMIN role required"),
        @ApiResponse(responseCode = "404", description = "Order not found")
    })
    public Mono<OrderResponse> getOrder(@PathVariable UUID orderId) {
        return getOrderUseCase.getById(orderId)
            .map(OrderResponse::from);
    }

    @PutMapping("/{orderId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update order status (Admin)",
               description = "Updates the order status. Only valid status transitions are allowed. " +
                   "Accessible only to ADMIN users.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Order status updated",
                     content = @Content(schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid status transition"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden - ADMIN role required"),
        @ApiResponse(responseCode = "404", description = "Order not found"),
        @ApiResponse(responseCode = "409", description = "Concurrent modification")
    })
    public Mono<OrderResponse> updateOrderStatus(@PathVariable UUID orderId,
                                                   @Valid @RequestBody UpdateOrderStatusRequest request) {
        return updateOrderStatusUseCase.execute(
                new UpdateOrderStatusCommand(orderId, request.status()))
            .map(OrderResponse::from);
    }
}
