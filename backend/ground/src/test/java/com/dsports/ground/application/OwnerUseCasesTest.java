package com.dsports.ground.application;

import com.dsports.ground.application.usecase.ManageBlackoutsUseCase;
import com.dsports.ground.application.usecase.ManageGroundsUseCase;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Ground;
import com.dsports.ground.domain.model.Slot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static com.dsports.ground.domain.GroundFixtures.IST;
import static com.dsports.ground.domain.GroundFixtures.NOW;
import static com.dsports.ground.domain.GroundFixtures.OWNER;
import static com.dsports.ground.domain.GroundFixtures.WEDNESDAY;
import static com.dsports.ground.domain.GroundFixtures.at;
import static com.dsports.ground.domain.GroundFixtures.spec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnerUseCasesTest {

    private static final UUID OTHER_OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private final Fakes.Grounds grounds = new Fakes.Grounds();
    private final Fakes.Bookings bookings = new Fakes.Bookings();
    private final Fakes.Blackouts blackouts = new Fakes.Blackouts();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ManageGroundsUseCase manageGrounds = new ManageGroundsUseCase(grounds, clock);
    private final ManageBlackoutsUseCase manageBlackouts = new ManageBlackoutsUseCase(grounds, bookings, blackouts, Fakes.IMMEDIATE, clock);

    @Test
    void aGroundIsCreatedForTheCallerAndListedOnlyForThem() {
        var created = manageGrounds.create(OWNER, spec()).block();
        manageGrounds.create(OTHER_OWNER, spec()).block();

        assertThat(created.ground().active()).isTrue();
        assertThat(grounds.store.get(created.ground().id()).getOwnerId()).isEqualTo(OWNER);
        assertThat(manageGrounds.list(OWNER).collectList().block()).hasSize(1);
        assertThat(manageGrounds.list(OTHER_OWNER).collectList().block()).hasSize(1);
    }

    @Test
    void invalidDetailsAreRejectedBeforeAnythingIsSaved() {
        var bad = new Ground.Spec("", "x", "y", null, null, spec().pitchType(), List.of(), List.of(), LocalTime.of(6, 0), LocalTime.of(22, 0), IST, 60,
            BigDecimal.TEN, null, null, null, null);

        assertCode(() -> manageGrounds.create(OWNER, bad).block(), GroundErrorCode.VALIDATION_ERROR);
        assertThat(grounds.store).isEmpty();
    }

    @Test
    void anOwnerCanEditTheirGroundAndDeactivateIt() {
        var created = manageGrounds.create(OWNER, spec()).block();
        var edited = new Ground.Spec("Renamed", "New Road", "Pune", null, null, spec().pitchType(), List.of(), List.of(), LocalTime.of(7, 0), LocalTime.of(21, 0),
            IST, 120, new BigDecimal("3000"), null, null, null, null);

        var updated = manageGrounds.update(OWNER, created.ground().id(), edited, false, created.version()).block();

        assertThat(updated.ground().name()).isEqualTo("Renamed");
        assertThat(updated.ground().active()).isFalse();
        assertThat(updated.version()).isGreaterThan(created.version());
    }

    @Test
    void anEditOnAStaleVersionIsRefusedAndChangesNothing() {
        var created = manageGrounds.create(OWNER, spec()).block();
        manageGrounds.update(OWNER, created.ground().id(), spec(), true, created.version()).block();

        assertCode(() -> manageGrounds.update(OWNER, created.ground().id(), spec(), false, created.version()).block(), GroundErrorCode.OPTIMISTIC_LOCKING_CONFLICT);
        assertThat(grounds.store.get(created.ground().id()).isActive()).isTrue();
    }

    @Test
    void aGroundOfAnotherOwnerCannotBeEditedOrBlackedOutOrInspected() {
        var created = manageGrounds.create(OWNER, spec()).block();
        var id = created.ground().id();

        assertCode(() -> manageGrounds.update(OTHER_OWNER, id, spec(), true, created.version()).block(), GroundErrorCode.GROUND_NOT_OWNED_BY_USER);
        assertCode(() -> manageBlackouts.add(OTHER_OWNER, id, at(WEDNESDAY, 9, 0), at(WEDNESDAY, 10, 0), "x").block(), GroundErrorCode.GROUND_NOT_OWNED_BY_USER);
        assertCode(() -> manageBlackouts.list(OTHER_OWNER, id, null, null).collectList().block(), GroundErrorCode.GROUND_NOT_OWNED_BY_USER);
        assertThat(blackouts.store).isEmpty();
    }

    @Test
    void aMissingGroundIsNotFound() {
        assertCode(() -> manageGrounds.update(OWNER, UUID.randomUUID(), spec(), true, 1).block(), GroundErrorCode.GROUND_NOT_FOUND);
        assertCode(() -> manageBlackouts.add(OWNER, UUID.randomUUID(), at(WEDNESDAY, 9, 0), at(WEDNESDAY, 10, 0), "x").block(), GroundErrorCode.GROUND_NOT_FOUND);
    }

    @Test
    void aBlackoutIsSavedUnderThePerGroundLock() {
        var id = manageGrounds.create(OWNER, spec()).block().ground().id();

        var blackout = manageBlackouts.add(OWNER, id, at(WEDNESDAY, 9, 0), at(WEDNESDAY, 12, 0), "  Pitch maintenance ").block();

        assertThat(blackout.reason()).isEqualTo("Pitch maintenance");
        assertThat(blackout.createdBy()).isEqualTo(OWNER);
        assertThat(blackouts.store).containsExactly(blackout);
        assertThat(bookings.locks).isEqualTo(1);
    }

    @Test
    void aBlackoutOverAnActiveBookingIsRefusedNotSilentlyCancelling() {
        var id = manageGrounds.create(OWNER, spec()).block().ground().id();
        bookings.occupied.add(new Slot(at(WEDNESDAY, 10, 0), at(WEDNESDAY, 11, 0)));

        assertCode(() -> manageBlackouts.add(OWNER, id, at(WEDNESDAY, 9, 0), at(WEDNESDAY, 12, 0), "x").block(), GroundErrorCode.BLACKOUT_CONFLICT);
        assertThat(blackouts.store).isEmpty();
    }

    @Test
    void aBlackoutMustBeWellFormedAndEndInTheFuture() {
        var id = manageGrounds.create(OWNER, spec()).block().ground().id();

        assertCode(() -> manageBlackouts.add(OWNER, id, at(WEDNESDAY, 12, 0), at(WEDNESDAY, 9, 0), "x").block(), GroundErrorCode.VALIDATION_ERROR);
        assertCode(() -> manageBlackouts.add(OWNER, id, at(WEDNESDAY, 9, 0), at(WEDNESDAY, 10, 0), " ").block(), GroundErrorCode.VALIDATION_ERROR);
        assertCode(() -> manageBlackouts.add(OWNER, id, NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(1)), "past").block(), GroundErrorCode.VALIDATION_ERROR);
    }

    @Test
    void theOwnerCanListTheirBlackoutsInARange() {
        var id = manageGrounds.create(OWNER, spec()).block().ground().id();
        manageBlackouts.add(OWNER, id, at(WEDNESDAY, 9, 0), at(WEDNESDAY, 12, 0), "x").block();

        assertThat(manageBlackouts.list(OWNER, id, null, null).collectList().block()).hasSize(1);
        assertThat(manageBlackouts.list(OWNER, id, at(WEDNESDAY, 13, 0), at(WEDNESDAY, 14, 0)).collectList().block()).isEmpty();
        assertCode(() -> manageBlackouts.list(OWNER, id, at(WEDNESDAY, 14, 0), at(WEDNESDAY, 13, 0)).collectList().block(), GroundErrorCode.VALIDATION_ERROR);
    }

    private static void assertCode(Callable<?> action, GroundErrorCode code) {
        assertThatThrownBy(action::call).isInstanceOfSatisfying(GroundDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
