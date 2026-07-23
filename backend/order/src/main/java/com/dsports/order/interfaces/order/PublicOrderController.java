package com.dsports.order.interfaces.order;

import com.dsports.order.application.order.command.CancelOrderCommand;
import com.dsports.order.application.order.command.PlaceOrderCommand;
import com.dsports.order.application.order.query.GetOrderQuery;
import com.dsports.order.application.order.query.GetOrdersQuery;
import com.dsports.order.application.order.usecase.CancelOrderUseCase;
import com.dsports.order.application.order.usecase.GetOrderUseCase;
import com.dsports.order.application.order.usecase.GetOrdersUseCase;
import com.dsports.order.application.order.usecase.PlaceOrderUseCase;
import com.dsports.order.interfaces.order.dto.OrderResponse;
import com.dsports.order.interfaces.order.dto.OrderSummaryResponse;
import com.dsports.order.interfaces.order.dto.PlaceOrderRequest;
import com.dsports.order.interfaces.order.dto.PlaceOrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/orders", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Orders")
public class PublicOrderController {

    private final PlaceOrderUseCase placeOrderUseCase;
    private final GetOrderUseCase getOrderUseCase;
    private final GetOrdersUseCase getOrdersUseCase;
    private final CancelOrderUseCase cancelOrderUseCase;

    public PublicOrderController(PlaceOrderUseCase placeOrderUseCase,
                                  GetOrderUseCase getOrderUseCase,
                                  GetOrdersUseCase getOrdersUseCase,
                                  CancelOrderUseCase cancelOrderUseCase) {
        this.placeOrderUseCase = placeOrderUseCase;
        this.getOrderUseCase = getOrderUseCase;
        this.getOrdersUseCase = getOrdersUseCase;
        this.cancelOrderUseCase = cancelOrderUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Place a new order",
               description = "Converts a validated checkout into a permanent customer order. " +
                   "Reserves inventory and captures price/address snapshots.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Order placed successfully",
                     content = @Content(schema = @Schema(implementation = PlaceOrderResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid checkout or checkout not validated"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Checkout not found"),
        @ApiResponse(responseCode = "409", description = "Duplicate order or concurrent modification")
    })
    public Mono<PlaceOrderResponse> placeOrder(Authentication authentication,
                                                @Valid @RequestBody PlaceOrderRequest request) {
        var userId = extractUserId(authentication);
        var command = new PlaceOrderCommand(userId, request.checkoutId());
        return placeOrderUseCase.execute(command)
            .map(PlaceOrderResponse::from);
    }

    @GetMapping
    @Operation(summary = "Get all orders for the authenticated user",
               description = "Returns a list of order summaries sorted by placed date descending.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "List of orders",
                     content = @Content(schema = @Schema(implementation = OrderSummaryResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Flux<OrderSummaryResponse> getOrders(Authentication authentication) {
        var userId = extractUserId(authentication);
        return getOrdersUseCase.execute(new GetOrdersQuery(userId))
            .map(OrderSummaryResponse::from);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get order details",
               description = "Returns the full order details including items, address snapshots, and pricing.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Order found",
                     content = @Content(schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden - order belongs to another user"),
        @ApiResponse(responseCode = "404", description = "Order not found")
    })
    public Mono<OrderResponse> getOrder(@PathVariable UUID orderId,
                                         Authentication authentication) {
        var userId = extractUserId(authentication);
        return getOrderUseCase.execute(new GetOrderQuery(orderId, userId))
            .map(OrderResponse::from);
    }

    @PutMapping("/{orderId}/cancel")
    @Operation(summary = "Cancel an order",
               description = "Cancels an order if it is in a cancellable state. " +
                   "Delivered and already cancelled orders cannot be cancelled.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Order cancelled",
                     content = @Content(schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "400", description = "Order cannot be cancelled"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden - order belongs to another user"),
        @ApiResponse(responseCode = "404", description = "Order not found"),
        @ApiResponse(responseCode = "409", description = "Concurrent modification")
    })
    public Mono<OrderResponse> cancelOrder(@PathVariable UUID orderId,
                                            Authentication authentication) {
        var userId = extractUserId(authentication);
        return cancelOrderUseCase.execute(new CancelOrderCommand(orderId, userId))
            .map(OrderResponse::from);
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        return UUID.fromString(authentication.getPrincipal().toString());
    }
}
