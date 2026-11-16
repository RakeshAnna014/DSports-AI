package com.dsports.identity.interfaces;

import com.dsports.identity.application.port.RoleAdminRepository.AuditEntry;
import com.dsports.identity.application.port.RoleAdminRepository.Holder;
import com.dsports.identity.application.usecase.ManageGroundOwnersUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Administrators manage who is a ground owner. The security config requires ADMIN for everything under {@code /api/v1/admin}; the acting
 * administrator's id always comes from the verified token.
 */
@RestController
@RequestMapping("/api/v1/admin/ground-owners")
@Tag(name = "Ground owner administration")
@SecurityRequirement(name = "bearer-jwt")
public class GroundOwnerAdminController {

    public record GrantRequest(@NotBlank @Size(max = 254) String email) {
    }

    public record OwnerResponse(UUID id, String email, String firstName, String lastName, Instant grantedAt) {
        static OwnerResponse from(Holder holder) {
            var m = holder.member();
            return new OwnerResponse(m.id(), m.email(), m.firstName(), m.lastName(), holder.grantedAt());
        }
    }

    public record GrantResponse(UUID id, String email, String firstName, String lastName, boolean changed) {
    }

    public record AuditResponse(UUID id, UUID actorId, UUID targetId, String targetEmail, String action, Instant occurredAt) {
        static AuditResponse from(AuditEntry e) {
            return new AuditResponse(e.id(), e.actorId(), e.targetId(), e.targetEmail(), e.action(), e.occurredAt());
        }
    }

    private final ManageGroundOwnersUseCase manageOwners;

    public GroundOwnerAdminController(ManageGroundOwnersUseCase manageOwners) {
        this.manageOwners = manageOwners;
    }

    @GetMapping
    @Operation(summary = "List ground owners", description = "Everyone who currently holds the ground-owner role, by email.")
    public Flux<OwnerResponse> list() {
        return manageOwners.list().map(OwnerResponse::from);
    }

    @PostMapping
    @Operation(summary = "Make a registered user a ground owner",
        description = "Gives the ground-owner role to the user with this email. 201 when newly given, 200 when they already had it. "
            + "The user must have registered; an unknown email is 404 USER_NOT_REGISTERED. An administrator cannot grant the role to themselves (403). "
            + "The change is recorded in the audit log. The user must sign in again for the role to appear in their token.")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Role given"), @ApiResponse(responseCode = "200", description = "Already an owner"),
        @ApiResponse(responseCode = "403", description = "Not an administrator, or granting to yourself"), @ApiResponse(responseCode = "404", description = "Nobody registered with that email")})
    public Mono<ResponseEntity<GrantResponse>> grant(Authentication authentication, @Valid @RequestBody GrantRequest request) {
        return manageOwners.grant(actor(authentication), request.email()).map(result -> {
            var m = result.member();
            return ResponseEntity.status(result.changed() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new GrantResponse(m.id(), m.email(), m.firstName(), m.lastName(), result.changed()));
        });
    }

    @DeleteMapping("/{userId}")
    @Operation(summary = "Remove the ground-owner role",
        description = "Takes the role away from this user and records it in the audit log. Their venues stop taking new bookings and are hidden from search straight away; "
            + "existing bookings stay valid; an administrator can reassign the venues to another owner. The role stays in the user's current access token until it expires "
            + "(up to 15 minutes).")
    @ApiResponses({@ApiResponse(responseCode = "204", description = "Role removed"), @ApiResponse(responseCode = "404", description = "No such user, or not an owner")})
    public Mono<ResponseEntity<Void>> revoke(Authentication authentication, @PathVariable UUID userId) {
        return manageOwners.revoke(actor(authentication), userId).thenReturn(ResponseEntity.noContent().<Void>build());
    }

    @GetMapping("/audit")
    @Operation(summary = "Audit log of ground-owner changes", description = "Who gave or removed the role, for whom and when, most recent first.")
    public Flux<AuditResponse> audit(@RequestParam(defaultValue = "50") int limit) {
        return manageOwners.audit(limit).map(AuditResponse::from);
    }

    private static UUID actor(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        return UUID.fromString(authentication.getPrincipal().toString());
    }
}
