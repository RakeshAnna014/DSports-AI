package com.dsports.identity.application.usecase;

import com.dsports.identity.application.port.RoleAdminRepository;
import com.dsports.identity.application.port.RoleAdminRepository.AuditEntry;
import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManageGroundOwnersUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000000ad");

    /** In-memory stand-in for the role tables: members by id, the role holders, and the audit log. */
    static final class Fake implements RoleAdminRepository {
        final Map<UUID, Member> members = new LinkedHashMap<>();
        final Set<UUID> owners = new HashSet<>();
        final List<AuditEntry> audit = new ArrayList<>();

        Member register(String email) {
            var member = new Member(UUID.randomUUID(), email, "First", "Last");
            members.put(member.id(), member);
            return member;
        }

        @Override
        public Mono<Member> findMemberByEmail(String email) {
            return Flux.fromIterable(members.values()).filter(m -> m.email().equalsIgnoreCase(email.trim())).next();
        }

        @Override
        public Mono<Member> findMemberById(UUID id) {
            return Mono.justOrEmpty(members.get(id));
        }

        @Override
        public Mono<Boolean> grant(UUID memberId, String role) {
            return Mono.just(owners.add(memberId));
        }

        @Override
        public Mono<Boolean> revoke(UUID memberId, String role) {
            return Mono.just(owners.remove(memberId));
        }

        @Override
        public Flux<Holder> holders(String role) {
            return Flux.fromIterable(owners).map(id -> new Holder(members.get(id), null));
        }

        @Override
        public Mono<Void> appendAudit(AuditEntry entry) {
            audit.add(entry);
            return Mono.empty();
        }

        @Override
        public Flux<AuditEntry> audit(String role, int limit) {
            return Flux.fromIterable(audit).take(limit);
        }
    }

    private final Fake roles = new Fake();
    private final TransactionRunner immediate = new TransactionRunner() {
        @Override
        public <T> Mono<T> inTransaction(Supplier<Mono<T>> work) {
            return Mono.defer(work);
        }
    };
    private final ManageGroundOwnersUseCase useCase = new ManageGroundOwnersUseCase(roles, immediate, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void grantingARegisteredUserTheRoleChangesItAndWritesTheAuditEntry() {
        var asha = roles.register("asha@example.com");

        var result = useCase.grant(ADMIN, "asha@example.com").block();

        assertThat(result.changed()).isTrue();
        assertThat(roles.owners).containsExactly(asha.id());
        assertThat(roles.audit).singleElement().satisfies(e -> {
            assertThat(e.actorId()).isEqualTo(ADMIN);
            assertThat(e.targetId()).isEqualTo(asha.id());
            assertThat(e.targetEmail()).isEqualTo("asha@example.com");
            assertThat(e.role()).isEqualTo("GROUND_OWNER");
            assertThat(e.action()).isEqualTo("GRANTED");
            assertThat(e.occurredAt()).isEqualTo(NOW);
        });
    }

    @Test
    void theEmailIsMatchedIgnoringCaseAndSpaces() {
        var asha = roles.register("asha@example.com");

        assertThat(useCase.grant(ADMIN, "  ASHA@Example.COM ").block().member().id()).isEqualTo(asha.id());
    }

    @Test
    void anEmailNobodyRegisteredWithIsRefusedWithAHintAndNothingChanges() {
        assertCode(() -> useCase.grant(ADMIN, "nobody@example.com").block(), ErrorCode.USER_NOT_REGISTERED);
        assertThatThrownBy(() -> useCase.grant(ADMIN, "nobody@example.com").block()).hasMessageContaining("Ask them to register first");
        assertThat(roles.owners).isEmpty();
        assertThat(roles.audit).isEmpty();
    }

    @Test
    void anAdministratorCannotGrantTheRoleToThemselves() {
        var admin = roles.register("admin@example.com");

        assertCode(() -> useCase.grant(admin.id(), "admin@example.com").block(), ErrorCode.CANNOT_CHANGE_OWN_ROLE);
        assertThat(roles.owners).isEmpty();
        assertThat(roles.audit).isEmpty();
    }

    @Test
    void grantingTheRoleAgainChangesAndRecordsNothing() {
        roles.register("asha@example.com");
        useCase.grant(ADMIN, "asha@example.com").block();

        var again = useCase.grant(ADMIN, "asha@example.com").block();

        assertThat(again.changed()).isFalse();
        assertThat(roles.audit).hasSize(1);
    }

    @Test
    void aBlankOrOversizedEmailIsAValidationError() {
        assertCode(() -> useCase.grant(ADMIN, " ").block(), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> useCase.grant(ADMIN, null).block(), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> useCase.grant(ADMIN, "a".repeat(255)).block(), ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void removingTheRoleTakesItAwayAndWritesTheAuditEntry() {
        var asha = roles.register("asha@example.com");
        useCase.grant(ADMIN, "asha@example.com").block();

        useCase.revoke(ADMIN, asha.id()).block();

        assertThat(roles.owners).isEmpty();
        assertThat(roles.audit).extracting(AuditEntry::action).containsExactly("GRANTED", "REVOKED");
        assertThat(roles.audit.get(1).actorId()).isEqualTo(ADMIN);
        assertThat(roles.audit.get(1).targetEmail()).isEqualTo("asha@example.com");
    }

    @Test
    void removingARoleThatIsNotHeldOrFromAnUnknownUserIsNotFoundAndRecordsNothing() {
        var asha = roles.register("asha@example.com");

        assertCode(() -> useCase.revoke(ADMIN, asha.id()).block(), ErrorCode.ROLE_NOT_HELD);
        assertCode(() -> useCase.revoke(ADMIN, UUID.randomUUID()).block(), ErrorCode.USER_NOT_FOUND);
        assertThat(roles.audit).isEmpty();
    }

    @Test
    void theListShowsCurrentOwnersAndTheAuditIsBounded() {
        var asha = roles.register("asha@example.com");
        var ben = roles.register("ben@example.com");
        useCase.grant(ADMIN, "asha@example.com").block();
        useCase.grant(ADMIN, "ben@example.com").block();
        useCase.revoke(ADMIN, ben.id()).block();

        assertThat(useCase.list().map(h -> h.member().id()).collectList().block()).containsExactly(asha.id());
        assertThat(useCase.audit(2).collectList().block()).hasSize(2);
        assertThat(useCase.audit(-5).collectList().block()).hasSize(1);
        assertThat(useCase.audit(100_000).collectList().block()).hasSize(3);
    }

    private static void assertCode(Callable<?> action, ErrorCode code) {
        assertThatThrownBy(action::call).isInstanceOfSatisfying(IdentityDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
