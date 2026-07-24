package com.dsports.payment.interfaces.payment;

import com.dsports.payment.application.payment.command.CancelPaymentCommand;
import com.dsports.payment.application.payment.command.CreatePaymentCommand;
import com.dsports.payment.application.payment.command.RefundPaymentCommand;
import com.dsports.payment.application.payment.query.GetPaymentHistoryQuery;
import com.dsports.payment.application.payment.query.GetPaymentQuery;
import com.dsports.payment.application.payment.usecase.CancelPaymentUseCase;
import com.dsports.payment.application.payment.usecase.CreatePaymentUseCase;
import com.dsports.payment.application.payment.usecase.GetPaymentHistoryUseCase;
import com.dsports.payment.application.payment.usecase.GetPaymentUseCase;
import com.dsports.payment.application.payment.usecase.RefundPaymentUseCase;
import com.dsports.payment.interfaces.payment.dto.CreatePaymentRequest;
import com.dsports.payment.interfaces.payment.dto.PaymentResponse;
import com.dsports.payment.interfaces.payment.dto.PaymentSummaryResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/payments", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Payments")
public class PublicPaymentController {

    private final CreatePaymentUseCase createPaymentUseCase;
    private final GetPaymentUseCase getPaymentUseCase;
    private final GetPaymentHistoryUseCase getPaymentHistoryUseCase;
    private final CancelPaymentUseCase cancelPaymentUseCase;
    private final RefundPaymentUseCase refundPaymentUseCase;

    public PublicPaymentController(CreatePaymentUseCase createPaymentUseCase,
                                    GetPaymentUseCase getPaymentUseCase,
                                    GetPaymentHistoryUseCase getPaymentHistoryUseCase,
                                    CancelPaymentUseCase cancelPaymentUseCase,
                                    RefundPaymentUseCase refundPaymentUseCase) {
        this.createPaymentUseCase = createPaymentUseCase;
        this.getPaymentUseCase = getPaymentUseCase;
        this.getPaymentHistoryUseCase = getPaymentHistoryUseCase;
        this.cancelPaymentUseCase = cancelPaymentUseCase;
        this.refundPaymentUseCase = refundPaymentUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new payment",
               description = "Initiates a payment for an order. Validates order ownership and amount. " +
                   "Returns payment details including gateway reference.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Payment created successfully",
                     content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request or amount mismatch"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Order not found"),
        @ApiResponse(responseCode = "409", description = "Order already paid or concurrent modification")
    })
    public Mono<PaymentResponse> createPayment(Authentication authentication,
                                                @Valid @RequestBody CreatePaymentRequest request) {
        var userId = extractUserId(authentication);
        var command = new CreatePaymentCommand(userId, request.orderId(), request.amount(),
            request.currency(), request.paymentMethod(), request.paymentProvider());
        return createPaymentUseCase.execute(command)
            .map(PaymentResponse::from);
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get payment details",
               description = "Returns the full payment details for a given payment ID.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Payment found",
                     content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden - payment belongs to another user"),
        @ApiResponse(responseCode = "404", description = "Payment not found")
    })
    public Mono<PaymentResponse> getPayment(@PathVariable UUID paymentId,
                                             Authentication authentication) {
        var userId = extractUserId(authentication);
        return getPaymentUseCase.execute(new GetPaymentQuery(paymentId, userId))
            .map(PaymentResponse::from);
    }

    @GetMapping("/history")
    @Operation(summary = "Get payment history for the authenticated user",
               description = "Returns a list of all payments made by the authenticated user.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Payment history",
                     content = @Content(schema = @Schema(implementation = PaymentSummaryResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Flux<PaymentSummaryResponse> getPaymentHistory(Authentication authentication) {
        var userId = extractUserId(authentication);
        return getPaymentHistoryUseCase.execute(new GetPaymentHistoryQuery(userId))
            .map(PaymentSummaryResponse::from);
    }

    @PostMapping("/{paymentId}/cancel")
    @Operation(summary = "Cancel a payment",
               description = "Cancels a payment if it is in a cancellable state. " +
                   "Successful payments cannot be cancelled.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Payment cancelled",
                     content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
        @ApiResponse(responseCode = "400", description = "Payment cannot be cancelled"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden - payment belongs to another user"),
        @ApiResponse(responseCode = "404", description = "Payment not found"),
        @ApiResponse(responseCode = "409", description = "Concurrent modification")
    })
    public Mono<PaymentResponse> cancelPayment(@PathVariable UUID paymentId,
                                                Authentication authentication) {
        var userId = extractUserId(authentication);
        return cancelPaymentUseCase.execute(new CancelPaymentCommand(paymentId, userId))
            .map(PaymentResponse::from);
    }

    @PostMapping("/{paymentId}/refund")
    @Operation(summary = "Refund a payment",
               description = "Initiates a refund for a successful payment. " +
                   "Only payments in SUCCESS status can be refunded.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Payment refunded",
                     content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
        @ApiResponse(responseCode = "400", description = "Payment cannot be refunded"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden - payment belongs to another user"),
        @ApiResponse(responseCode = "404", description = "Payment not found"),
        @ApiResponse(responseCode = "409", description = "Concurrent modification")
    })
    public Mono<PaymentResponse> refundPayment(@PathVariable UUID paymentId,
                                                Authentication authentication) {
        var userId = extractUserId(authentication);
        return refundPaymentUseCase.execute(new RefundPaymentCommand(paymentId, userId))
            .map(PaymentResponse::from);
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        return UUID.fromString(authentication.getPrincipal().toString());
    }
}
