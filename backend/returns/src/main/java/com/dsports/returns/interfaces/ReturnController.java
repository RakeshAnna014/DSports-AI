package com.dsports.returns.interfaces;

import com.dsports.returns.application.usecase.CheckReturnEligibilityUseCase;
import com.dsports.returns.application.usecase.CreateReturnUseCase;
import com.dsports.returns.application.usecase.ManageReturnsUseCase;
import com.dsports.returns.domain.policy.ReturnEligibility;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Returns")
public class ReturnController {

    private final CheckReturnEligibilityUseCase checkEligibility;
    private final CreateReturnUseCase createReturn;
    private final ManageReturnsUseCase manageReturns;

    public ReturnController(CheckReturnEligibilityUseCase checkEligibility, CreateReturnUseCase createReturn,
                            ManageReturnsUseCase manageReturns) {
        this.checkEligibility = checkEligibility;
        this.createReturn = createReturn;
        this.manageReturns = manageReturns;
    }

    @GetMapping("/orders/{orderId}/returns/eligibility")
    @Operation(summary = "Return eligibility of a product in one of my orders",
               description = "Authoritative policy answer: delivered, inside the return window, remaining quantity.")
    public Mono<ReturnEligibility> eligibility(@PathVariable UUID orderId, @RequestParam UUID productId,
                                               Authentication authentication) {
        return checkEligibility.execute(orderId, productId, userId(authentication));
    }

    @PostMapping("/returns")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Request a return",
               description = "Idempotent on the Idempotency-Key header; eligibility is re-checked server-side.")
    public Mono<ReturnResponse> create(@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                       @Valid @RequestBody CreateReturnRequest request, Authentication authentication) {
        return createReturn.execute(new CreateReturnUseCase.Command(userId(authentication), request.orderId(),
                request.productId(), request.quantity(), request.reason(), idempotencyKey))
            .map(ReturnResponse::from);
    }

    @GetMapping("/returns")
    @Operation(summary = "My return requests")
    public Flux<ReturnResponse> mine(Authentication authentication) {
        return manageReturns.mine(userId(authentication)).map(ReturnResponse::from);
    }

    @GetMapping("/returns/{id}")
    @Operation(summary = "One of my return requests")
    public Mono<ReturnResponse> get(@PathVariable UUID id, Authentication authentication) {
        return manageReturns.get(id, userId(authentication), false).map(ReturnResponse::from);
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getPrincipal().toString());
    }
}
