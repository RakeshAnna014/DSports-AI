package com.dsports.identity.application.usecase;

import com.dsports.identity.application.port.RoleAdminRepository;
import com.dsports.identity.application.port.RoleAdminRepository.AuditEntry;
import com.dsports.identity.application.port.RoleAdminRepository.Holder;
import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.shared.application.TransactionRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.UUID;

/**
 * Administrators giving and taking away the ground-owner role without touching the database by hand.
 * <ul>
 *   <li>Only the already-registered can be given the role: an unknown email is {@code USER_NOT_REGISTERED} ("ask them to register first").</li>
 *   <li>An administrator cannot give the role to themselves.</li>
 *   <li>Only this one role can be changed here; the screen can never be used to hand out an administrator role.</li>
 *   <li>Every real grant or removal is recorded in the audit log in the same transaction as the change; asking for what is already true changes
 *       and records nothing.</li>
 * </ul>
 * A role removed here stays in the person's access token until it expires (up to 15 minutes by default): routes protected only by the token keep
 * working until then. Everything that decides from the live data (a venue taking bookings) reacts at once.
 */
public class ManageGroundOwnersUseCase {
    private static final Logger log = LoggerFactory.getLogger(ManageGroundOwnersUseCase.class);

    /** The one role this use case manages. */
    public static final String ROLE = "GROUND_OWNER";
    private static final int MAX_AUDIT = 200;

    /** {@code changed} is false when the member already held the role. */
    public record GrantResult(RoleAdminRepository.Member member, boolean changed) {
    }

    private final RoleAdminRepository roles;
    private final TransactionRunner transactions;
    private final Clock clock;

    public ManageGroundOwnersUseCase(RoleAdminRepository roles, TransactionRunner transactions, Clock clock) {
        this.roles = roles;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Flux<Holder> list() {
        return roles.holders(ROLE);
    }

    public Flux<AuditEntry> audit(int limit) {
        return roles.audit(ROLE, Math.max(1, Math.min(limit, MAX_AUDIT)));
    }

    public Mono<GrantResult> grant(UUID actorId, String email) {
        if (email == null || email.isBlank() || email.length() > 254) {
            return Mono.error(new IdentityDomainException(ErrorCode.VALIDATION_ERROR, "An email address is required"));
        }
        return transactions.inTransaction(() -> roles.findMemberByEmail(email.trim())
            .switchIfEmpty(Mono.error(new IdentityDomainException(ErrorCode.USER_NOT_REGISTERED, "No registered user has that email. Ask them to register first.")))
            .flatMap(member -> {
                if (member.id().equals(actorId)) {
                    return Mono.<GrantResult>error(new IdentityDomainException(ErrorCode.CANNOT_CHANGE_OWN_ROLE, "You cannot grant a role to yourself"));
                }
                return roles.grant(member.id(), ROLE).flatMap(changed -> changed
                    ? audit(actorId, member, "GRANTED").thenReturn(new GrantResult(member, true))
                    : Mono.just(new GrantResult(member, false)));
            }));
    }

    public Mono<Void> revoke(UUID actorId, UUID memberId) {
        return transactions.inTransaction(() -> roles.findMemberById(memberId)
            .switchIfEmpty(Mono.error(new IdentityDomainException(ErrorCode.USER_NOT_FOUND, "No such user")))
            .flatMap(member -> roles.revoke(memberId, ROLE).flatMap(changed -> changed
                ? audit(actorId, member, "REVOKED")
                : Mono.<Void>error(new IdentityDomainException(ErrorCode.ROLE_NOT_HELD, "That user is not a ground owner")))));
    }

    private Mono<Void> audit(UUID actorId, RoleAdminRepository.Member member, String action) {
        log.info("Role {} {} for user {} by administrator {}", ROLE, action, member.id(), actorId);
        return roles.appendAudit(new AuditEntry(UUID.randomUUID(), actorId, member.id(), member.email(), ROLE, action, clock.instant()));
    }
}
