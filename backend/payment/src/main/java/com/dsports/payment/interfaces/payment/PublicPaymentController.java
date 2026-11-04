package com.dsports.payment.interfaces.payment;

import com.dsports.payment.application.payment.command.CancelPaymentCommand;
import com.dsports.payment.application.payment.command.CapturePaymentCommand;
import com.dsports.payment.application.payment.command.CreatePaymentCommand;
import com.dsports.payment.application.payment.query.GetPaymentHistoryQuery;
import com.dsports.payment.application.payment.query.GetPaymentQuery;
import com.dsports.payment.application.payment.usecase.CancelPaymentUseCase;
import com.dsports.payment.application.payment.usecase.CapturePaymentUseCase;
import com.dsports.payment.application.payment.usecase.CreatePaymentUseCase;
import com.dsports.payment.application.payment.usecase.GetPaymentHistoryUseCase;
import com.dsports.payment.application.payment.usecase.GetPaymentUseCase;
import com.dsports.payment.application.refund.ManageRefundsUseCase;
import com.dsports.payment.application.refund.RefundResult;
import com.dsports.payment.application.refund.RequestRefundUseCase;
import com.dsports.payment.interfaces.payment.dto.CreatePaymentRequest;
import com.dsports.payment.interfaces.payment.dto.PaymentResponse;
import com.dsports.payment.interfaces.payment.dto.PaymentSummaryResponse;
import com.dsports.payment.interfaces.payment.dto.RefundRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final CapturePaymentUseCase capturePaymentUseCase;
    private final GetPaymentUseCase getPaymentUseCase;
    private final GetPaymentHistoryUseCase getPaymentHistoryUseCase;
    private final CancelPaymentUseCase cancelPaymentUseCase;
    private final RequestRefundUseCase requestRefundUseCase;
    private final ManageRefundsUseCase manageRefundsUseCase;

    public PublicPaymentController(CreatePaymentUseCase createPaymentUseCase,
                                    CapturePaymentUseCase capturePaymentUseCase,
                                    GetPaymentUseCase getPaymentUseCase,
                                    GetPaymentHistoryUseCase getPaymentHistoryUseCase,
                                    CancelPaymentUseCase cancelPaymentUseCase,
                                    RequestRefundUseCase requestRefundUseCase,
                                    ManageRefundsUseCase manageRefundsUseCase) {
        this.createPaymentUseCase = createPaymentUseCase;
        this.capturePaymentUseCase = capturePaymentUseCase;
        this.getPaymentUseCase = getPaymentUseCase;
        this.getPaymentHistoryUseCase = getPaymentHistoryUseCase;
        this.cancelPaymentUseCase = cancelPaymentUseCase;
        this.requestRefundUseCase = requestRefundUseCase;
        this.manageRefundsUseCase = manageRefundsUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Authorize a payment for an order",
               description = "Authorizes the order total with the payment gateway. The amount and currency must match "
                   + "the order. Idempotent per order: while an attempt is open (AUTHORIZED or PROCESSING) that attempt "
                   + "is returned. A FAILED or CANCELLED attempt allows a new one.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Payment authorized, declined (status FAILED) or open attempt returned",
                     content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request or amount mismatch"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Order belongs to another user"),
        @ApiResponse(responseCode = "404", description = "Order not found"),
        @ApiResponse(responseCode = "409", description = "Order already paid, not payable, or concurrent payment")
    })
    public Mono<PaymentResponse> createPayment(Authentication authentication,
                                                @Valid @RequestBody CreatePaymentRequest request) {
        var userId = extractUserId(authentication);
        var command = new CreatePaymentCommand(userId, request.orderId(), request.amount(),
            request.currency(), request.paymentMethod(), request.paymentProvider());
        return createPaymentUseCase.execute(command)
            .map(PaymentResponse::from);
    }

    @PostMapping("/{paymentId}/capture")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Capture an authorized payment",
               description = "Captures the authorized amount and confirms the order (stock fulfilled, cart completed, "
                   + "shipment and notifications scheduled). Safe to retry: an already captured payment is returned "
                   + "unchanged. A decline returns status FAILED.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Captured (SUCCESS) or declined (FAILED)",
                     content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Payment belongs to another user"),
        @ApiResponse(responseCode = "404", description = "Payment not found"),
        @ApiResponse(responseCode = "409", description = "Payment not capturable or order no longer payable"),
        @ApiResponse(responseCode = "502", description = "Gateway unavailable; retry the capture"),
        @ApiResponse(responseCode = "504", description = "Gateway timed out; retry the capture")
    })
    public Mono<PaymentResponse> capturePayment(@PathVariable UUID paymentId, Authentication authentication) {
        var userId = extractUserId(authentication);
        return capturePaymentUseCase.execute(new CapturePaymentCommand(paymentId, userId))
            .map(PaymentResponse::from);
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get payment details")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Payment found",
                     content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Payment belongs to another user"),
        @ApiResponse(responseCode = "404", description = "Payment not found")
    })
    public Mono<PaymentResponse> getPayment(@PathVariable UUID paymentId, Authentication authentication) {
        var userId = extractUserId(authentication);
        return getPaymentUseCase.execute(new GetPaymentQuery(paymentId, userId))
            .map(PaymentResponse::from);
    }

    @GetMapping("/history")
    @Operation(summary = "Payment history of the authenticated user")
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
    @Operation(summary = "Cancel an uncaptured payment",
               description = "Voids an authorized payment. Captured payments cannot be cancelled; request a refund instead.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Payment cancelled",
                     content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Payment belongs to another user"),
        @ApiResponse(responseCode = "404", description = "Payment not found"),
        @ApiResponse(responseCode = "409", description = "Payment captured, capture in progress, or already terminal")
    })
    public Mono<PaymentResponse> cancelPayment(@PathVariable UUID paymentId, Authentication authentication) {
        var userId = extractUserId(authentication);
        return cancelPaymentUseCase.execute(new CancelPaymentCommand(paymentId, userId))
            .map(PaymentResponse::from);
    }

    @PostMapping("/{paymentId}/refund-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Request a refund",
               description = "Records a refund request for the caller's own captured payment. Nothing is paid out until "
                   + "an administrator approves it. Omit the amount to request the full remaining amount. Idempotent on "
                   + "the Idempotency-Key header.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Refund requested (or the original request for this key)"),
        @ApiResponse(responseCode = "400", description = "Missing Idempotency-Key or invalid amount"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Payment belongs to another user"),
        @ApiResponse(responseCode = "404", description = "Payment not found"),
        @ApiResponse(responseCode = "409", description = "Payment not refundable")
    })
    public Mono<RefundResult> requestRefund(@PathVariable UUID paymentId,
                                            @Parameter(description = "Client-generated key; retries reuse it")
                                            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                            @Valid @RequestBody(required = false) RefundRequest request,
                                            Authentication authentication) {
        var userId = extractUserId(authentication);
        var body = request == null ? new RefundRequest(null, null) : request;
        return requestRefundUseCase.execute(new RequestRefundUseCase.Command(paymentId, userId, true,
                body.amount(), body.reason(), idempotencyKey))
            .map(RefundResult::from);
    }

    @GetMapping("/{paymentId}/refunds")
    @Operation(summary = "Refunds of one of the caller's payments")
    public Flux<RefundResult> getRefunds(@PathVariable UUID paymentId, Authentication authentication) {
        var userId = extractUserId(authentication);
        return manageRefundsUseCase.forPayment(paymentId, userId, false).map(RefundResult::from);
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        return UUID.fromString(authentication.getPrincipal().toString());
    }
}
