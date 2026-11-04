package com.dsports.support.interfaces;

import com.dsports.support.application.usecase.CreateSupportCaseUseCase;
import com.dsports.support.application.usecase.CustomerCaseUseCase;
import com.dsports.support.domain.exception.SupportDomainException;
import com.dsports.support.domain.exception.SupportErrorCode;
import com.dsports.support.domain.model.CaseCategory;
import com.dsports.support.domain.model.EvidenceItem;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The customer's own support cases. The customer is always the authenticated user; a case of anybody else is "not found".
 */
@RestController
@RequestMapping(path = "/api/v1/support/cases", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Support Cases")
public class SupportCaseController {

    private final CreateSupportCaseUseCase create;
    private final CustomerCaseUseCase customer;
    private final Clock clock;

    public SupportCaseController(CreateSupportCaseUseCase create, CustomerCaseUseCase customer, Clock clock) {
        this.create = create;
        this.customer = customer;
        this.clock = clock;
    }

    @PostMapping
    @Operation(summary = "Open a support case",
        description = "Idempotent on the Idempotency-Key header. Returns outcome CREATED, REPLAYED (same key) or ALREADY_OPEN "
            + "(an unresolved case of this category about this order already exists; no second case is created).")
    public Mono<ResponseEntity<SupportResponses.CreateResult>> open(@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                                                    @Valid @RequestBody SupportRequests.CreateCaseRequest r, Authentication authentication) {
        var userId = userId(authentication);
        var command = new CreateSupportCaseUseCase.Command(userId, idempotencyKey, category(r.category()), blankToNull(r.subcategory()), r.subject().trim(),
            r.description().trim(), r.orderId(), r.productId(), r.paymentId(), r.returnId(), blankToNull(r.refundReference()), r.safetyRelated(),
            blankToNull(r.investigationSummary()),
            r.evidence() == null ? List.<EvidenceItem>of() : r.evidence().stream().map(e -> new EvidenceItem(e.kind() == null ? "OTHER" : e.kind(), e.ref(), e.summary(), null)).toList(),
            r.policyCitations(), r.missingInformation(), blankToNull(r.resolutionRecommendation()), blankToNull(r.escalationReason()), r.source());
        return create.execute(command).flatMap(result -> customer.get(result.supportCase().getId().toString(), userId)
            .map(view -> ResponseEntity.status(result.outcome() == CreateSupportCaseUseCase.Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK)
                .body(SupportResponses.created(result, view, customer.reopenWindow(), clock.instant()))));
    }

    @GetMapping
    @Operation(summary = "My support cases", description = "Newest activity first. unresolved=true hides resolved cases.")
    public Flux<SupportResponses.Summary> mine(@RequestParam(defaultValue = "false") boolean unresolved, Authentication authentication) {
        return customer.mine(userId(authentication), unresolved).map(SupportResponses::summary);
    }

    @GetMapping("/{ref}")
    @Operation(summary = "One of my support cases", description = "ref is the case id or the case number (CASE-YYYYMMDD-NNNNNN). Internal notes are never included.")
    public Mono<SupportResponses.Customer> get(@PathVariable String ref, Authentication authentication) {
        return customer.get(ref, userId(authentication)).map(this::view);
    }

    @PostMapping("/{ref}/messages")
    @Operation(summary = "Add a message to my case", description = "Not allowed on a resolved case. Answering a request for information moves the case back to INVESTIGATING.")
    public Mono<SupportResponses.Customer> message(@PathVariable String ref, @Valid @RequestBody SupportRequests.MessageRequest r, Authentication authentication) {
        return customer.addMessage(ref, userId(authentication), r.body()).map(this::view);
    }

    @PostMapping("/{ref}/escalate")
    @Operation(summary = "Ask for a human to handle my case",
        description = "Records an escalation REQUEST. It does not mean a support executive has accepted the case: escalation.humanAccepted says that.")
    public Mono<SupportResponses.Customer> escalate(@PathVariable String ref, @Valid @RequestBody SupportRequests.ReasonRequest r, Authentication authentication) {
        return customer.requestEscalation(ref, userId(authentication), r.reason()).map(this::view);
    }

    @PostMapping("/{ref}/reopen")
    @Operation(summary = "Reopen my resolved case", description = "Only within the reopen window after resolution (see reopenWindowDays on the case).")
    public Mono<SupportResponses.Customer> reopen(@PathVariable String ref, @Valid @RequestBody SupportRequests.ReasonRequest r, Authentication authentication) {
        return customer.reopen(ref, userId(authentication), r.reason()).map(this::view);
    }

    private SupportResponses.Customer view(CustomerCaseUseCase.CaseView v) {
        return SupportResponses.customer(v, customer.reopenWindow(), clock.instant());
    }

    static CaseCategory category(String raw) {
        try {
            return CaseCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SupportDomainException(SupportErrorCode.VALIDATION_ERROR, "Unknown category. Allowed: " + java.util.Arrays.toString(CaseCategory.values()));
        }
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getPrincipal().toString());
    }
}
