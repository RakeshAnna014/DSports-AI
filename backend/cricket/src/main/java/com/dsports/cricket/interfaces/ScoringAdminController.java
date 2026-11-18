package com.dsports.cricket.interfaces;

import com.dsports.cricket.application.result.ScoringView;
import com.dsports.cricket.application.usecase.ScoringUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Administrators reopen a match whose 24-hour correction window has closed. The route is for ADMIN only (checked by the security configuration and again by the use case). */
@RestController
@RequestMapping(path = "/api/v1/admin/matches/{matchId}/scoring", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Cricket scoring (admin)")
public class ScoringAdminController {

    private final ScoringUseCase scoring;

    public ScoringAdminController(ScoringUseCase scoring) {
        this.scoring = scoring;
    }

    @PostMapping(path = "/unlock", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Unlock a locked match", description = "Allows scoring and undo for 24 hours from now. A reason is required and is kept, with who did it and when, in the match's event log.")
    public Mono<ScoringView.Outcome> unlock(Authentication authentication, @PathVariable UUID matchId, @Valid @RequestBody ScoringRequests.UnlockRequest request) {
        return scoring.unlock(Principals.actor(authentication), matchId, request.clientEventId() == null ? UUID.randomUUID() : request.clientEventId(), request.reason());
    }
}
