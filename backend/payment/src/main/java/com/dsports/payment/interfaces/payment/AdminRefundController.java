package com.dsports.payment.interfaces.payment;

import com.dsports.payment.application.refund.ManageRefundsUseCase;
import com.dsports.payment.application.refund.ProcessRefundUseCase;
import com.dsports.payment.application.refund.RefundResult;
import com.dsports.payment.application.refund.RequestRefundUseCase;
import com.dsports.payment.interfaces.payment.dto.RefundRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Refund administration: the only place where money is returned to customers. */
@RestController
@RequestMapping(path = "/api/v1/admin", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Refunds")
public class AdminRefundController {

    private final RequestRefundUseCase requestRefundUseCase;
    private final ProcessRefundUseCase processRefundUseCase;
    private final ManageRefundsUseCase manageRefundsUseCase;

    public AdminRefundController(RequestRefundUseCase requestRefundUseCase,
                                 ProcessRefundUseCase processRefundUseCase,
                                 ManageRefundsUseCase manageRefundsUseCase) {
        this.requestRefundUseCase = requestRefundUseCase;
        this.processRefundUseCase = processRefundUseCase;
        this.manageRefundsUseCase = manageRefundsUseCase;
    }

    public record RejectRefundRequest(@Size(max = 500) String reason) {}

    @GetMapping("/refunds")
    @Operation(summary = "List all refunds, newest first")
    public Flux<RefundResult> listRefunds() {
        return manageRefundsUseCase.all().map(RefundResult::from);
    }

    @GetMapping("/payments/{paymentId}/refunds")
    @Operation(summary = "Refunds of a payment")
    public Flux<RefundResult> paymentRefunds(@PathVariable UUID paymentId, Authentication authentication) {
        return manageRefundsUseCase.forPayment(paymentId, adminId(authentication), true).map(RefundResult::from);
    }

    @PostMapping("/payments/{paymentId}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Refund a payment now",
               description = "Creates and immediately processes a refund. Idempotent on the Idempotency-Key header.")
    public Mono<RefundResult> refundPayment(@PathVariable UUID paymentId,
                                            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                            @Valid @RequestBody(required = false) RefundRequest request,
                                            Authentication authentication) {
        var body = request == null ? new RefundRequest(null, null) : request;
        return requestRefundUseCase.execute(new RequestRefundUseCase.Command(paymentId, adminId(authentication), false,
                body.amount(), body.reason(), idempotencyKey))
            .flatMap(refund -> switch (refund.getStatus()) {
                case REQUESTED, FAILED -> processRefundUseCase.execute(refund.getId());
                default -> Mono.just(refund);
            })
            .map(RefundResult::from);
    }

    @PostMapping("/refunds/{refundId}/approve")
    @Operation(summary = "Approve and process a requested refund, or retry a FAILED / unconfirmed PROCESSING one")
    public Mono<RefundResult> approve(@PathVariable UUID refundId) {
        return processRefundUseCase.execute(refundId).map(RefundResult::from);
    }

    @PostMapping("/refunds/{refundId}/reject")
    @Operation(summary = "Reject a requested refund")
    public Mono<RefundResult> reject(@PathVariable UUID refundId,
                                     @Valid @RequestBody(required = false) RejectRefundRequest request) {
        return manageRefundsUseCase.reject(refundId, request == null ? null : request.reason())
            .map(RefundResult::from);
    }

    private static UUID adminId(Authentication authentication) {
        return UUID.fromString(authentication.getPrincipal().toString());
    }
}
