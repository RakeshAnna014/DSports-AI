package com.dsports.returns.interfaces;

import com.dsports.returns.application.usecase.ManageReturnsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/admin/returns", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Returns")
public class AdminReturnController {

    private final ManageReturnsUseCase manageReturns;

    public AdminReturnController(ManageReturnsUseCase manageReturns) {
        this.manageReturns = manageReturns;
    }

    @GetMapping
    @Operation(summary = "All return requests")
    public Flux<ReturnResponse> all() {
        return manageReturns.all().map(ReturnResponse::from);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a requested return")
    public Mono<ReturnResponse> approve(@PathVariable UUID id, @Valid @RequestBody(required = false) DecisionRequest body) {
        return manageReturns.approve(id, body == null ? null : body.note()).map(ReturnResponse::from);
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject a requested return")
    public Mono<ReturnResponse> reject(@PathVariable UUID id, @Valid @RequestBody(required = false) DecisionRequest body) {
        return manageReturns.reject(id, body == null ? null : body.note()).map(ReturnResponse::from);
    }
}
