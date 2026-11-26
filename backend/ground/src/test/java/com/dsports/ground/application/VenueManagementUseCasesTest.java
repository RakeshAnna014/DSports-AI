package com.dsports.ground.application;

import com.dsports.ground.application.usecase.Caller;
import com.dsports.ground.application.usecase.GetCourtSlotsUseCase;
import com.dsports.ground.application.usecase.GetVenueUseCase;
import com.dsports.ground.application.usecase.ManageBlackoutsUseCase;
import com.dsports.ground.application.usecase.ManageCourtsUseCase;
import com.dsports.ground.application.usecase.ManageVenuesUseCase;
import com.dsports.ground.application.usecase.SearchVenuesUseCase;
import com.dsports.ground.application.usecase.VenueAssembler;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Court;
import com.dsports.ground.domain.model.Slot;
import com.dsports.ground.domain.model.Venue;
import com.dsports.ground.domain.policy.BookingPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static com.dsports.ground.domain.GroundFixtures.BADMINTON;
import static com.dsports.ground.domain.GroundFixtures.CRICKET;
import static com.dsports.ground.domain.GroundFixtures.FOOTBALL;
import static com.dsports.ground.domain.GroundFixtures.IST;
import static com.dsports.ground.domain.GroundFixtures.NOW;
import static com.dsports.ground.domain.GroundFixtures.OWNER;
import static com.dsports.ground.domain.GroundFixtures.WEDNESDAY;
import static com.dsports.ground.domain.GroundFixtures.at;
import static com.dsports.ground.domain.GroundFixtures.courtSpec;
import static com.dsports.ground.domain.GroundFixtures.venueSpec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Owners and administrators managing venues, courts and blackouts, and what customers see of them. */
class VenueManagementUseCasesTest {

    private static final UUID OTHER_OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000000ad");
    private static final UUID SQUASH = UUID.fromString("00000000-0000-0000-0000-00000000a001");

    private final Fakes.Venues venues = new Fakes.Venues();
    private final Fakes.Courts courts = new Fakes.Courts();
    private final Fakes.Bookings bookings = new Fakes.Bookings();
    private final Fakes.Blackouts blackouts = new Fakes.Blackouts();
    private final Fakes.Owners owners = new Fakes.Owners();
    private final Fakes.Sports sports = new Fakes.Sports().with(CRICKET, "Cricket", true).with(FOOTBALL, "Football", true)
        .with(BADMINTON, "Badminton", true).with(SQUASH, "Squash", false);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final VenueAssembler assembler = new VenueAssembler(courts, sports, owners);
    private final ManageVenuesUseCase manageVenues = new ManageVenuesUseCase(venues, courts, owners, assembler, clock);
    private final ManageCourtsUseCase manageCourts = new ManageCourtsUseCase(venues, courts, sports, assembler, clock);
    private final ManageBlackoutsUseCase manageBlackouts = new ManageBlackoutsUseCase(venues, courts, bookings, blackouts, Fakes.IMMEDIATE, clock);
    private final GetCourtSlotsUseCase slots = new GetCourtSlotsUseCase(venues, courts, bookings, blackouts, owners, BookingPolicy.defaults(), clock);
    private final SearchVenuesUseCase search = new SearchVenuesUseCase(venues, courts, slots, assembler);
    private final GetVenueUseCase getVenue = new GetVenueUseCase(venues, courts, assembler);

    private final Caller owner = Caller.owner(OWNER);
    private final Caller other = Caller.owner(OTHER_OWNER);
    private final Caller admin = Caller.administrator(ADMIN);

    {
        venues.courts = courts;
        owners.withRole.add(OWNER);
        owners.withRole.add(OTHER_OWNER);
    }

    private UUID venueOf(Caller caller, UUID ownerId) {
        return manageVenues.create(caller, ownerId, venueSpec()).block().venue().id();
    }

    private Court.Spec court(String name, UUID... sportIds) {
        var s = courtSpec();
        return new Court.Spec(name, "turf", List.of(sportIds), 60, s.basePrice(), s.weekendPrice(), s.peakPrice(), s.peakFrom(), s.peakTo());
    }

    // ---- venues -----------------------------------------------------------------------------------------------

    @Test
    void aVenueIsCreatedForTheCallerAndListedOnlyForThem() {
        var created = manageVenues.create(owner, null, venueSpec()).block();
        manageVenues.create(other, null, venueSpec()).block();

        assertThat(created.venue().active()).isTrue();
        assertThat(created.ownerId()).isEqualTo(OWNER);
        assertThat(created.ownerEligible()).isTrue();
        assertThat(manageVenues.list(owner).collectList().block()).hasSize(1);
        assertThat(manageVenues.list(other).collectList().block()).hasSize(1);
    }

    @Test
    void anOwnerCannotCreateAVenueForSomeoneElseWhateverTheyAsk() {
        var created = manageVenues.create(owner, OTHER_OWNER, venueSpec()).block();

        assertThat(created.ownerId()).isEqualTo(OWNER);
    }

    @Test
    void anAdministratorCreatesAVenueOnAnOwnersBehalfButOnlyForAUserWhoHoldsTheRole() {
        var created = manageVenues.create(admin, OTHER_OWNER, venueSpec()).block();

        assertThat(created.ownerId()).isEqualTo(OTHER_OWNER);
        assertCode(() -> manageVenues.create(admin, UUID.randomUUID(), venueSpec()).block(), GroundErrorCode.OWNER_NOT_ELIGIBLE);
        assertCode(() -> manageVenues.create(admin, null, venueSpec()).block(), GroundErrorCode.VALIDATION_ERROR);
        assertThat(venues.store).hasSize(1);
    }

    @Test
    void invalidDetailsAreRejectedBeforeAnythingIsSaved() {
        var s = venueSpec();
        var bad = new Venue.Spec("", s.address(), s.city(), null, null, List.of(), List.of(), s.opensAt(), s.closesAt(), IST);

        assertCode(() -> manageVenues.create(owner, null, bad).block(), GroundErrorCode.VALIDATION_ERROR);
        assertThat(venues.store).isEmpty();
    }

    @Test
    void anOwnerCanEditTheirVenueAndDeactivateIt() {
        var created = manageVenues.create(owner, null, venueSpec()).block();
        var s = venueSpec();
        var edited = new Venue.Spec("Renamed", "New Road", "Pune", null, null, List.of(), List.of(), LocalTime.of(7, 0), LocalTime.of(21, 0), IST);

        var updated = manageVenues.update(owner, created.venue().id(), edited, false, created.version(), null).block();

        assertThat(updated.venue().name()).isEqualTo("Renamed");
        assertThat(updated.venue().active()).isFalse();
        assertThat(updated.version()).isGreaterThan(created.version());
        assertThat(s.name()).isNotEqualTo("Renamed");
    }

    @Test
    void anEditOnAStaleVersionIsRefusedAndChangesNothing() {
        var created = manageVenues.create(owner, null, venueSpec()).block();
        manageVenues.update(owner, created.venue().id(), venueSpec(), true, created.version(), null).block();

        assertCode(() -> manageVenues.update(owner, created.venue().id(), venueSpec(), false, created.version(), null).block(), GroundErrorCode.OPTIMISTIC_LOCKING_CONFLICT);
        assertThat(venues.store.get(created.venue().id()).isActive()).isTrue();
    }

    @Test
    void aVenueOfAnotherOwnerCannotBeEditedOrBlackedOutOrInspected() {
        var created = manageVenues.create(owner, null, venueSpec()).block();
        var id = created.venue().id();

        assertCode(() -> manageVenues.update(other, id, venueSpec(), true, created.version(), null).block(), GroundErrorCode.VENUE_NOT_OWNED_BY_USER);
        assertCode(() -> manageVenues.get(other, id).block(), GroundErrorCode.VENUE_NOT_OWNED_BY_USER);
        assertCode(() -> manageCourts.create(other, id, court("Court 1", CRICKET)).block(), GroundErrorCode.VENUE_NOT_OWNED_BY_USER);
        assertCode(() -> manageBlackouts.add(other, id, null, at(WEDNESDAY, 9, 0), at(WEDNESDAY, 10, 0), "x").block(), GroundErrorCode.VENUE_NOT_OWNED_BY_USER);
        assertCode(() -> manageBlackouts.list(other, id, null, null).collectList().block(), GroundErrorCode.VENUE_NOT_OWNED_BY_USER);
        assertThat(blackouts.store).isEmpty();
        assertThat(courts.store).isEmpty();
    }

    @Test
    void anAdministratorCanEditAnyVenueAndSeesThemAll() {
        var created = manageVenues.create(owner, null, venueSpec()).block();
        manageVenues.create(other, null, venueSpec()).block();
        var s = venueSpec();
        var edited = new Venue.Spec("Fixed by admin", s.address(), s.city(), null, null, List.of(), List.of(), s.opensAt(), s.closesAt(), IST);

        var updated = manageVenues.update(admin, created.venue().id(), edited, true, created.version(), null).block();

        assertThat(updated.venue().name()).isEqualTo("Fixed by admin");
        assertThat(updated.ownerId()).isEqualTo(OWNER);
        assertThat(manageVenues.list(admin).collectList().block()).hasSize(2);
    }

    @Test
    void aMissingVenueIsNotFound() {
        assertCode(() -> manageVenues.update(owner, UUID.randomUUID(), venueSpec(), true, 1, null).block(), GroundErrorCode.VENUE_NOT_FOUND);
        assertCode(() -> manageBlackouts.add(owner, UUID.randomUUID(), null, at(WEDNESDAY, 9, 0), at(WEDNESDAY, 10, 0), "x").block(), GroundErrorCode.VENUE_NOT_FOUND);
    }

    @Test
    void theHoursCannotShrinkBelowACourtsSlotLength() {
        var created = manageVenues.create(owner, null, venueSpec()).block();
        manageCourts.create(owner, created.venue().id(), court("Court 1", CRICKET)).block();
        var s = venueSpec();
        var tight = new Venue.Spec(s.name(), s.address(), s.city(), null, null, List.of(), List.of(), LocalTime.of(6, 0), LocalTime.of(6, 30), IST);

        assertCode(() -> manageVenues.update(owner, created.venue().id(), tight, true, created.version(), null).block(), GroundErrorCode.VALIDATION_ERROR);
    }

    // ---- ownership: removing the role and reassigning -----------------------------------------------------------

    @Test
    void aVenueWhoseOwnerLostTheRoleIsFlaggedAndHiddenFromSearchButStillThere() {
        var venue = manageVenues.create(owner, null, venueSpec()).block();
        manageCourts.create(owner, venue.venue().id(), court("Court 1", CRICKET)).block();
        assertThat(search.execute(CRICKET, "bengaluru", null, null, null).collectList().block()).hasSize(1);

        owners.withRole.remove(OWNER);

        assertThat(search.execute(CRICKET, "bengaluru", null, null, null).collectList().block()).isEmpty();
        var seen = manageVenues.get(admin, venue.venue().id()).block();
        assertThat(seen.ownerEligible()).isFalse();
        assertThat(seen.venue().active()).isTrue();
        assertThat(getVenue.execute(venue.venue().id()).block().acceptingBookings()).isFalse();
    }

    @Test
    void anAdministratorCanReassignTheVenueToAnotherOwnerAndItIsBookableAgain() {
        var venue = manageVenues.create(owner, null, venueSpec()).block();
        manageCourts.create(owner, venue.venue().id(), court("Court 1", CRICKET)).block();
        owners.withRole.remove(OWNER);

        var moved = manageVenues.update(admin, venue.venue().id(), venueSpec(), true, venue.version(), OTHER_OWNER).block();

        assertThat(moved.ownerId()).isEqualTo(OTHER_OWNER);
        assertThat(moved.ownerEligible()).isTrue();
        assertThat(search.execute(CRICKET, "Bengaluru", null, null, null).collectList().block()).hasSize(1);
        assertThat(getVenue.execute(venue.venue().id()).block().acceptingBookings()).isTrue();
        assertThat(manageVenues.list(other).collectList().block()).hasSize(1);
    }

    @Test
    void aVenueCanOnlyBeReassignedToAUserWhoHoldsTheRole() {
        var venue = manageVenues.create(owner, null, venueSpec()).block();

        assertCode(() -> manageVenues.update(admin, venue.venue().id(), venueSpec(), true, venue.version(), UUID.randomUUID()).block(), GroundErrorCode.OWNER_NOT_ELIGIBLE);
        assertThat(venues.store.get(venue.venue().id()).getOwnerId()).isEqualTo(OWNER);
    }

    @Test
    void anOwnerCannotReassignTheirOwnVenue() {
        var venue = manageVenues.create(owner, null, venueSpec()).block();

        assertCode(() -> manageVenues.update(owner, venue.venue().id(), venueSpec(), true, venue.version(), OTHER_OWNER).block(), GroundErrorCode.VENUE_NOT_OWNED_BY_USER);
        assertThat(venues.store.get(venue.venue().id()).getOwnerId()).isEqualTo(OWNER);
    }

    @Test
    void theSlotsOfAVenueWhoseOwnerLostTheRoleAreAllUnavailable() {
        var venue = manageVenues.create(owner, null, venueSpec()).block();
        var court = manageCourts.create(owner, venue.venue().id(), court("Court 1", CRICKET)).block();
        owners.withRole.remove(OWNER);

        var day = slots.execute(venue.venue().id(), court.id(), WEDNESDAY.plusDays(2)).block();

        assertThat(day).isNotEmpty().allMatch(s -> !s.available() && "NOT_ACCEPTING".equals(s.unavailableReason()));
    }

    // ---- courts -----------------------------------------------------------------------------------------------

    @Test
    void aCourtOffersSeveralBookableSportsAndShowsTheirNames() {
        var venue = manageVenues.create(owner, null, venueSpec()).block();

        var court = manageCourts.create(owner, venue.venue().id(), court("Court 1", CRICKET, FOOTBALL)).block();

        assertThat(court.sports()).extracting(s -> s.name()).containsExactly("Cricket", "Football");
        assertThat(court.venueId()).isEqualTo(venue.venue().id());
        assertThat(manageVenues.get(owner, venue.venue().id()).block().courts()).hasSize(1);
    }

    @Test
    void aSportThatIsNotBookableOrUnknownCannotBeAddedToACourt() {
        var venue = manageVenues.create(owner, null, venueSpec()).block();

        assertCode(() -> manageCourts.create(owner, venue.venue().id(), court("Court 1", CRICKET, SQUASH)).block(), GroundErrorCode.SPORT_NOT_BOOKABLE);
        assertCode(() -> manageCourts.create(owner, venue.venue().id(), court("Court 1", UUID.randomUUID())).block(), GroundErrorCode.SPORT_NOT_BOOKABLE);
        assertCode(() -> manageCourts.create(owner, venue.venue().id(), court("Court 1")).block(), GroundErrorCode.VALIDATION_ERROR);
        assertThat(courts.store).isEmpty();
    }

    @Test
    void courtNamesAreUniqueAtAVenueInAnyCaseButFreeAtAnotherVenue() {
        var first = venueOf(owner, null);
        var second = venueOf(owner, null);
        manageCourts.create(owner, first, court("Court 1", CRICKET)).block();

        assertCode(() -> manageCourts.create(owner, first, court("court 1", FOOTBALL)).block(), GroundErrorCode.DUPLICATE_COURT_NAME);
        assertThat(manageCourts.create(owner, second, court("Court 1", CRICKET)).block().name()).isEqualTo("Court 1");
    }

    @Test
    void aCourtCanBeEditedSwitchedToOtherSportsAndDeactivated() {
        var venue = manageVenues.create(owner, null, venueSpec()).block();
        var court = manageCourts.create(owner, venue.venue().id(), court("Court 1", CRICKET)).block();

        var updated = manageCourts.update(owner, venue.venue().id(), court.id(), court("Court A", BADMINTON), false, court.version()).block();

        assertThat(updated.name()).isEqualTo("Court A");
        assertThat(updated.sports()).extracting(s -> s.name()).containsExactly("Badminton");
        assertThat(updated.active()).isFalse();
        assertThat(updated.version()).isGreaterThan(court.version());
    }

    @Test
    void aStaleCourtEditAndAnotherVenuesCourtAreRefused() {
        var venue = manageVenues.create(owner, null, venueSpec()).block();
        var elsewhere = venueOf(owner, null);
        var court = manageCourts.create(owner, venue.venue().id(), court("Court 1", CRICKET)).block();
        manageCourts.update(owner, venue.venue().id(), court.id(), court("Court 1", CRICKET), true, court.version()).block();

        assertCode(() -> manageCourts.update(owner, venue.venue().id(), court.id(), court("Court 1", CRICKET), true, court.version()).block(), GroundErrorCode.OPTIMISTIC_LOCKING_CONFLICT);
        assertCode(() -> manageCourts.update(owner, elsewhere, court.id(), court("Court 1", CRICKET), true, 1).block(), GroundErrorCode.COURT_NOT_FOUND);
        assertCode(() -> manageCourts.update(other, venue.venue().id(), court.id(), court("Court 1", CRICKET), true, 1).block(), GroundErrorCode.VENUE_NOT_OWNED_BY_USER);
    }

    @Test
    void anAdministratorCanAddACourtToAnOwnersVenue() {
        var venue = manageVenues.create(owner, null, venueSpec()).block();

        var court = manageCourts.create(admin, venue.venue().id(), court("Court 1", CRICKET)).block();

        assertThat(court.name()).isEqualTo("Court 1");
    }

    // ---- what customers see -----------------------------------------------------------------------------------

    @Test
    void searchFindsOnlyVenuesWithACourtOfferingTheSportAndMatchesTheCityIgnoringCase() {
        var hub = manageVenues.create(owner, null, venueSpec()).block().venue().id();
        manageCourts.create(owner, hub, court("Court 1", CRICKET, FOOTBALL)).block();
        manageCourts.create(owner, hub, court("Court 2", BADMINTON)).block();
        var elsewhere = manageVenues.create(other, null, new Venue.Spec("Pune Arena", "1 Road", "Pune", null, null, List.of(), List.of(), LocalTime.of(6, 0), LocalTime.of(22, 0), IST)).block().venue().id();
        manageCourts.create(other, elsewhere, court("Hall", BADMINTON)).block();

        assertThat(search.execute(CRICKET, "BENGALURU", null, null, null).collectList().block()).hasSize(1);
        assertThat(search.execute(CRICKET, "  bengaluru  ", null, null, null).collectList().block()).hasSize(1);
        assertThat(search.execute(CRICKET, "Pune", null, null, null).collectList().block()).isEmpty();
        assertThat(search.execute(BADMINTON, null, null, null, null).collectList().block()).hasSize(2);
        assertThat(search.execute(null, "pune", null, null, null).collectList().block()).hasSize(1);

        var cricket = search.execute(CRICKET, "Bengaluru", null, null, null).collectList().block().get(0);
        assertThat(cricket.courts()).extracting(c -> c.name()).containsExactly("Court 1");
    }

    @Test
    void searchWithADateReportsFreeSlotsPerCourtAndLeavesOutFullyBookedOnes() {
        var hub = manageVenues.create(owner, null, venueSpec()).block().venue().id();
        var one = manageCourts.create(owner, hub, court("Court 1", CRICKET)).block();
        manageCourts.create(owner, hub, court("Court 2", CRICKET)).block();
        var date = WEDNESDAY.plusDays(2);
        bookings.occupied.add(new Slot(at(date, 6, 0), at(date, 22, 0)));

        // The fake occupied list applies to every court, so both are full: the venue drops out.
        assertThat(search.execute(CRICKET, "Bengaluru", date, null, null).collectList().block()).isEmpty();

        bookings.occupied.clear();
        var hit = search.execute(CRICKET, "Bengaluru", date, null, null).collectList().block().get(0);
        assertThat(hit.courts()).hasSize(2);
        assertThat(hit.availableSlots()).isEqualTo(32);
        assertThat(hit.lowestAvailablePrice()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(one.venueId()).isEqualTo(hub);
    }

    @Test
    void searchRejectsAWindowWithoutADateAndAReversedWindow() {
        assertCode(() -> search.execute(CRICKET, null, null, LocalTime.of(9, 0), null).collectList().block(), GroundErrorCode.VALIDATION_ERROR);
        assertCode(() -> search.execute(CRICKET, null, WEDNESDAY, LocalTime.of(11, 0), LocalTime.of(9, 0)).collectList().block(), GroundErrorCode.VALIDATION_ERROR);
    }

    @Test
    void anInactiveVenueOrCourtIsHiddenFromCustomers() {
        var venue = manageVenues.create(owner, null, venueSpec()).block();
        var court = manageCourts.create(owner, venue.venue().id(), court("Court 1", CRICKET)).block();

        manageCourts.update(owner, venue.venue().id(), court.id(), court("Court 1", CRICKET), false, court.version()).block();
        assertThat(search.execute(CRICKET, null, null, null, null).collectList().block()).isEmpty();
        assertCode(() -> slots.execute(venue.venue().id(), court.id(), WEDNESDAY).block(), GroundErrorCode.COURT_NOT_FOUND);

        manageVenues.update(owner, venue.venue().id(), venueSpec(), false, venue.version(), null).block();
        assertCode(() -> getVenue.execute(venue.venue().id()).block(), GroundErrorCode.VENUE_NOT_FOUND);
    }

    // ---- blackouts --------------------------------------------------------------------------------------------

    @Test
    void aBlackoutIsSavedUnderThePerVenueLockForTheWholeVenueOrOneCourt() {
        var venue = manageVenues.create(owner, null, venueSpec()).block().venue().id();
        var court = manageCourts.create(owner, venue, court("Court 1", CRICKET)).block();

        var whole = manageBlackouts.add(owner, venue, null, at(WEDNESDAY, 9, 0), at(WEDNESDAY, 12, 0), "  Pitch maintenance ").block();
        var one = manageBlackouts.add(owner, venue, court.id(), at(WEDNESDAY, 13, 0), at(WEDNESDAY, 14, 0), "Net repair").block();

        assertThat(whole.reason()).isEqualTo("Pitch maintenance");
        assertThat(whole.courtId()).isNull();
        assertThat(whole.createdBy()).isEqualTo(OWNER);
        assertThat(one.courtId()).isEqualTo(court.id());
        assertThat(blackouts.store).containsExactly(whole, one);
        assertThat(bookings.locks).isEqualTo(2);
    }

    @Test
    void aBlackoutNamingACourtOfAnotherVenueIsRefused() {
        var venue = venueOf(owner, null);
        var elsewhere = venueOf(owner, null);
        var court = manageCourts.create(owner, elsewhere, court("Court 1", CRICKET)).block();

        assertCode(() -> manageBlackouts.add(owner, venue, court.id(), at(WEDNESDAY, 9, 0), at(WEDNESDAY, 10, 0), "x").block(), GroundErrorCode.COURT_NOT_FOUND);
        assertThat(blackouts.store).isEmpty();
    }

    @Test
    void aBlackoutOverAnActiveBookingIsRefusedNotSilentlyCancelling() {
        var id = venueOf(owner, null);
        bookings.occupied.add(new Slot(at(WEDNESDAY, 10, 0), at(WEDNESDAY, 11, 0)));

        assertCode(() -> manageBlackouts.add(owner, id, null, at(WEDNESDAY, 9, 0), at(WEDNESDAY, 12, 0), "x").block(), GroundErrorCode.BLACKOUT_CONFLICT);
        assertThat(blackouts.store).isEmpty();
    }

    @Test
    void aBlackoutMustBeWellFormedAndEndInTheFuture() {
        var id = venueOf(owner, null);

        assertCode(() -> manageBlackouts.add(owner, id, null, at(WEDNESDAY, 12, 0), at(WEDNESDAY, 9, 0), "x").block(), GroundErrorCode.VALIDATION_ERROR);
        assertCode(() -> manageBlackouts.add(owner, id, null, at(WEDNESDAY, 9, 0), at(WEDNESDAY, 10, 0), " ").block(), GroundErrorCode.VALIDATION_ERROR);
        assertCode(() -> manageBlackouts.add(owner, id, null, NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(1)), "past").block(), GroundErrorCode.VALIDATION_ERROR);
    }

    @Test
    void theOwnerCanListTheirBlackoutsInARange() {
        var id = venueOf(owner, null);
        manageBlackouts.add(owner, id, null, at(WEDNESDAY, 9, 0), at(WEDNESDAY, 12, 0), "x").block();

        assertThat(manageBlackouts.list(owner, id, null, null).collectList().block()).hasSize(1);
        assertThat(manageBlackouts.list(owner, id, at(WEDNESDAY, 13, 0), at(WEDNESDAY, 14, 0)).collectList().block()).isEmpty();
        assertCode(() -> manageBlackouts.list(owner, id, at(WEDNESDAY, 14, 0), at(WEDNESDAY, 13, 0)).collectList().block(), GroundErrorCode.VALIDATION_ERROR);
    }

    private static void assertCode(Callable<?> action, GroundErrorCode code) {
        assertThatThrownBy(action::call).isInstanceOfSatisfying(GroundDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
