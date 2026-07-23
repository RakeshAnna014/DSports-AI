package com.dsports.order.interfaces.checkout;

import com.dsports.order.application.checkout.command.CreateCheckoutCommand;
import com.dsports.order.application.checkout.command.SelectDeliveryMethodCommand;
import com.dsports.order.application.checkout.command.SelectShippingAddressCommand;
import com.dsports.order.application.checkout.result.CheckoutResult;
import com.dsports.order.application.checkout.usecase.CancelCheckoutUseCase;
import com.dsports.order.application.checkout.usecase.CreateCheckoutUseCase;
import com.dsports.order.application.checkout.usecase.GetCheckoutUseCase;
import com.dsports.order.application.checkout.usecase.SelectDeliveryMethodUseCase;
import com.dsports.order.application.checkout.usecase.SelectShippingAddressUseCase;
import com.dsports.order.application.checkout.usecase.ValidateCheckoutUseCase;
import com.dsports.order.interfaces.checkout.dto.CheckoutResponse;
import com.dsports.order.interfaces.checkout.dto.CreateCheckoutResponse;
import com.dsports.order.interfaces.checkout.dto.SelectAddressRequest;
import com.dsports.order.interfaces.checkout.dto.SelectDeliveryMethodRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/checkout", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Checkout")
public class PublicCheckoutController {

    private final CreateCheckoutUseCase createCheckoutUseCase;
    private final GetCheckoutUseCase getCheckoutUseCase;
    private final SelectShippingAddressUseCase selectShippingAddressUseCase;
    private final SelectDeliveryMethodUseCase selectDeliveryMethodUseCase;
    private final ValidateCheckoutUseCase validateCheckoutUseCase;
    private final CancelCheckoutUseCase cancelCheckoutUseCase;

    public PublicCheckoutController(CreateCheckoutUseCase createCheckoutUseCase,
                                     GetCheckoutUseCase getCheckoutUseCase,
                                     SelectShippingAddressUseCase selectShippingAddressUseCase,
                                     SelectDeliveryMethodUseCase selectDeliveryMethodUseCase,
                                     ValidateCheckoutUseCase validateCheckoutUseCase,
                                     CancelCheckoutUseCase cancelCheckoutUseCase) {
        this.createCheckoutUseCase = createCheckoutUseCase;
        this.getCheckoutUseCase = getCheckoutUseCase;
        this.selectShippingAddressUseCase = selectShippingAddressUseCase;
        this.selectDeliveryMethodUseCase = selectDeliveryMethodUseCase;
        this.validateCheckoutUseCase = validateCheckoutUseCase;
        this.cancelCheckoutUseCase = cancelCheckoutUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new checkout",
               description = "Creates a checkout from the active cart. Validates inventory and pricing. " +
                   "Only one active checkout per customer is allowed.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Checkout created",
                     content = @Content(schema = @Schema(implementation = CreateCheckoutResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request (empty cart, already has active checkout)"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Cart not found")
    })
    public Mono<CreateCheckoutResponse> createCheckout(Authentication authentication) {
        var customerId = extractUserId(authentication);
        var command = new CreateCheckoutCommand(customerId);
        return createCheckoutUseCase.execute(command)
            .map(CreateCheckoutResponse::from);
    }

    @GetMapping("/{checkoutId}")
    @Operation(summary = "Get checkout details",
               description = "Returns the full checkout details including items, totals, address, and delivery method.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Checkout found",
                     content = @Content(schema = @Schema(implementation = CheckoutResponse.class))),
        @ApiResponse(responseCode = "404", description = "Checkout not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<CheckoutResponse> getCheckout(@PathVariable UUID checkoutId,
                                               Authentication authentication) {
        var customerId = extractUserId(authentication);
        return getCheckoutUseCase.execute(checkoutId, customerId)
            .map(CheckoutResponse::from);
    }

    @GetMapping("/active")
    @Operation(summary = "Get active checkout",
               description = "Returns the currently active (non-expired, non-cancelled) checkout for the customer.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Active checkout found",
                     content = @Content(schema = @Schema(implementation = CheckoutResponse.class))),
        @ApiResponse(responseCode = "404", description = "No active checkout"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<ResponseEntity<CheckoutResponse>> getActiveCheckout(Authentication authentication) {
        var customerId = extractUserId(authentication);
        return getCheckoutUseCase.getActiveCheckout(customerId)
            .map(CheckoutResponse::from)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping("/{checkoutId}/address")
    @Operation(summary = "Select shipping address",
               description = "Selects a shipping address for the checkout. The address must belong to the customer.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Address selected",
                     content = @Content(schema = @Schema(implementation = CheckoutResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(responseCode = "404", description = "Checkout not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<CheckoutResponse> selectAddress(@PathVariable UUID checkoutId,
                                                 @Valid @RequestBody SelectAddressRequest request,
                                                 Authentication authentication) {
        var customerId = extractUserId(authentication);
        var command = new SelectShippingAddressCommand(checkoutId, customerId, request.addressId());
        return selectShippingAddressUseCase.execute(command)
            .map(CheckoutResponse::from);
    }

    @PostMapping("/{checkoutId}/delivery")
    @Operation(summary = "Select delivery method",
               description = "Selects a delivery method (STANDARD, EXPRESS, or NEXT_DAY) for the checkout.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Delivery method selected",
                     content = @Content(schema = @Schema(implementation = CheckoutResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid delivery method"),
        @ApiResponse(responseCode = "404", description = "Checkout not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<CheckoutResponse> selectDeliveryMethod(@PathVariable UUID checkoutId,
                                                        @Valid @RequestBody SelectDeliveryMethodRequest request,
                                                        Authentication authentication) {
        var customerId = extractUserId(authentication);
        var command = new SelectDeliveryMethodCommand(checkoutId, customerId, request.deliveryMethodCode());
        return selectDeliveryMethodUseCase.execute(command)
            .map(CheckoutResponse::from);
    }

    @PostMapping("/{checkoutId}/validate")
    @Operation(summary = "Validate checkout",
               description = "Validates the checkout by re-checking inventory, confirming address and delivery method. " +
                   "Transitions to VALIDATED status.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Checkout validated",
                     content = @Content(schema = @Schema(implementation = CheckoutResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failed"),
        @ApiResponse(responseCode = "404", description = "Checkout not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<CheckoutResponse> validateCheckout(@PathVariable UUID checkoutId,
                                                    Authentication authentication) {
        var customerId = extractUserId(authentication);
        return validateCheckoutUseCase.execute(checkoutId, customerId)
            .map(CheckoutResponse::from);
    }

    @PostMapping("/{checkoutId}/cancel")
    @Operation(summary = "Cancel checkout",
               description = "Cancels the checkout, releasing any held resources.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Checkout cancelled",
                     content = @Content(schema = @Schema(implementation = CheckoutResponse.class))),
        @ApiResponse(responseCode = "404", description = "Checkout not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<CheckoutResponse> cancelCheckout(@PathVariable UUID checkoutId,
                                                   Authentication authentication) {
        var customerId = extractUserId(authentication);
        return cancelCheckoutUseCase.execute(checkoutId, customerId)
            .map(CheckoutResponse::from);
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        return UUID.fromString(authentication.getPrincipal().toString());
    }
}
