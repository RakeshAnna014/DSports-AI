package com.dsports.identity.application.port;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Role administration reads and writes, kept apart from the user aggregate so granting or removing one role can never overwrite the rest of a
 * user's record. Every change and its audit entry are written by the caller inside one transaction.
 */
public interface RoleAdminRepository {

    record Member(UUID id, String email, String firstName, String lastName) {
    }

    /** A member holding the role; {@code grantedAt} is the last recorded grant (null when the role was given outside the administration screens). */
    record Holder(Member member, Instant grantedAt) {
    }

    record AuditEntry(UUID id, UUID actorId, UUID targetId, String targetEmail, String role, String action, Instant occurredAt) {
    }

    /** The active (not deleted) member with this email, ignoring case. */
    Mono<Member> findMemberByEmail(String email);

    Mono<Member> findMemberById(UUID id);

    /** @return true when the role was newly given, false when the member already had it. */
    Mono<Boolean> grant(UUID memberId, String role);

    /** @return true when the role was removed, false when the member did not have it. */
    Mono<Boolean> revoke(UUID memberId, String role);

    Flux<Holder> holders(String role);

    Mono<Void> appendAudit(AuditEntry entry);

    /** Most recent first. */
    Flux<AuditEntry> audit(String role, int limit);
}
