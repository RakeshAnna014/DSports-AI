package com.dsports.cricket.application;

import com.dsports.cricket.application.port.CustomerDirectoryPort;
import com.dsports.cricket.application.usecase.Actor;
import com.dsports.cricket.application.usecase.ManagePlayersUseCase;
import com.dsports.cricket.application.usecase.PlayerClaimsUseCase;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.BattingHand;
import com.dsports.cricket.domain.model.Player;
import com.dsports.cricket.domain.model.PlayingRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayersAndClaimsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final Actor CAPTAIN = Actor.user(UUID.fromString("00000000-0000-0000-0000-0000000000c1"));
    private static final Actor ARJUN = Actor.user(UUID.fromString("00000000-0000-0000-0000-0000000000c2"));
    private static final Actor STRANGER = Actor.user(UUID.fromString("00000000-0000-0000-0000-0000000000c3"));
    private static final Actor ADMIN = new Actor(UUID.fromString("00000000-0000-0000-0000-0000000000ad"), true);

    private final Fakes.Players players = new Fakes.Players();
    private final Fakes.Claims claimStore = new Fakes.Claims();
    private final Fakes.Directory directory = new Fakes.Directory();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ManagePlayersUseCase managePlayers = new ManagePlayersUseCase(players, clock);
    private final PlayerClaimsUseCase claims = new PlayerClaimsUseCase(players, claimStore, directory, Fakes.IMMEDIATE, clock);

    {
        claimStore.players = players;
        directory.people.put(ARJUN.id(), new CustomerDirectoryPort.Person("Arjun Kumar", "arjun@example.com"));
        directory.people.put(STRANGER.id(), new CustomerDirectoryPort.Person("Sam Stranger", "sam@example.com"));
    }

    private static Player.Details details(String name, String phone) {
        return new Player.Details(name, phone, PlayingRole.ALL_ROUNDER, BattingHand.RIGHT, null, null);
    }

    private static void assertCode(Callable<?> action, CricketErrorCode code) {
        assertThatThrownBy(action::call).isInstanceOfSatisfying(CricketDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private UUID guest(Actor creator, String name, String phone) {
        return managePlayers.createGuest(creator, details(name, phone)).block().id();
    }

    // ---- players ---------------------------------------------------------------------------------------------

    @Test
    void aGuestIsCreatedForTheCallerAndOnlyTheyCanSeeItsPhone() {
        var result = managePlayers.createGuest(CAPTAIN, details("Arjun Kumar", "98765 43210")).block();

        assertThat(result.phone()).isEqualTo("+919876543210");
        assertThat(result.linked()).isFalse();
        assertThat(result.mine()).isFalse();
        assertThat(players.store.get(result.id()).getCreatedBy()).isEqualTo(CAPTAIN.id());
    }

    @Test
    void thePhoneIsUniqueAmongOneCreatorsPlayersButAnotherCreatorMayAddTheSamePerson() {
        guest(CAPTAIN, "Arjun", "9876543210");

        assertCode(() -> managePlayers.createGuest(CAPTAIN, details("Arjun again", "+91 98765 43210")).block(), CricketErrorCode.DUPLICATE_PLAYER_PHONE);
        assertThat(managePlayers.createGuest(STRANGER, details("Arjun K", "9876543210")).block().id()).isNotNull();
    }

    @Test
    void aPlayerIsVisibleOnlyToItsCreatorAndAnyoneElseGetsNotFound() {
        var id = guest(CAPTAIN, "Arjun", "9876543210");

        assertThat(managePlayers.get(CAPTAIN, id).block().name()).isEqualTo("Arjun");
        assertCode(() -> managePlayers.get(STRANGER, id).block(), CricketErrorCode.PLAYER_NOT_FOUND);
        assertCode(() -> managePlayers.get(CAPTAIN, UUID.randomUUID()).block(), CricketErrorCode.PLAYER_NOT_FOUND);
        assertThat(managePlayers.listMine(STRANGER).collectList().block()).isEmpty();
        assertThat(managePlayers.listMine(CAPTAIN).collectList().block()).hasSize(1);
    }

    @Test
    void aCustomersOwnProfileIsCreatedOnceAndAskingAgainReturnsIt() {
        var first = managePlayers.createOwn(ARJUN, details("Arjun Kumar", null)).block();
        var again = managePlayers.createOwn(ARJUN, details("A different name", null)).block();

        assertThat(first.linked()).isTrue();
        assertThat(first.mine()).isTrue();
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.name()).isEqualTo("Arjun Kumar");
        assertThat(players.store).hasSize(1);
    }

    @Test
    void anEditNeedsTheVersionTheCallerSawAndKeepsTheOwnership() {
        var created = managePlayers.createGuest(CAPTAIN, details("Arjun", null)).block();

        var updated = managePlayers.update(CAPTAIN, created.id(), details("Arjun Kumar", "9876543210"), created.version()).block();

        assertThat(updated.name()).isEqualTo("Arjun Kumar");
        assertThat(updated.version()).isGreaterThan(created.version());
        assertCode(() -> managePlayers.update(CAPTAIN, created.id(), details("Late edit", null), created.version()).block(), CricketErrorCode.OPTIMISTIC_LOCKING_CONFLICT);
        assertCode(() -> managePlayers.update(STRANGER, created.id(), details("Hijack", null), updated.version()).block(), CricketErrorCode.PLAYER_NOT_FOUND);
        assertThat(players.store.get(created.id()).getName()).isEqualTo("Arjun Kumar");
    }

    @Test
    void searchingByPhoneReturnsOnlyShortenedNamesOfUnlinkedGuestsAndAtMostFive() {
        for (int i = 0; i < 7; i++) {
            guest(Actor.user(UUID.randomUUID()), "Arjun Kumar " + i, "9876543210");
        }
        var own = managePlayers.createOwn(ARJUN, details("Linked Person", "9876543210")).block();

        var found = managePlayers.findClaimable(STRANGER, "98765 43210").collectList().block();

        assertThat(found).hasSize(5);
        assertThat(found).allSatisfy(c -> assertThat(c.displayName()).startsWith("Arjun ").endsWith("."));
        assertThat(found).extracting(c -> c.playerId()).doesNotContain(own.id());
        assertThat(managePlayers.findClaimable(STRANGER, "9000000000").collectList().block()).isEmpty();
        assertCode(() -> managePlayers.findClaimable(STRANGER, " ").collectList().block(), CricketErrorCode.VALIDATION_ERROR);
        assertCode(() -> managePlayers.findClaimable(STRANGER, "12").collectList().block(), CricketErrorCode.VALIDATION_ERROR);
    }

    // ---- claims ----------------------------------------------------------------------------------------------

    @Test
    void aClaimIsNeverGrantedByItselfAndItsCreatorSeesWhoIsAsking() {
        var id = guest(CAPTAIN, "Arjun Kumar", "9876543210");

        var claim = claims.request(ARJUN, id).block();

        assertThat(claim.status()).isEqualTo("PENDING");
        assertThat(players.store.get(id).isLinked()).isFalse();
        var incoming = claims.incoming(CAPTAIN).collectList().block();
        assertThat(incoming).singleElement().satisfies(c -> {
            assertThat(c.claimantName()).isEqualTo("Arjun Kumar");
            assertThat(c.claimantEmail()).isEqualTo("arjun@example.com");
            assertThat(c.playerName()).isEqualTo("Arjun Kumar");
        });
        assertThat(claims.incoming(STRANGER).collectList().block()).isEmpty();
        assertThat(claims.mine(ARJUN).collectList().block()).singleElement().satisfies(c -> assertThat(c.claimantEmail()).isNull());
    }

    @Test
    void theCreatorApprovesAndTheGuestBecomesTheClaimantsOwnProfile() {
        var id = guest(CAPTAIN, "Arjun Kumar", "9876543210");
        var claim = claims.request(ARJUN, id).block();

        var approved = claims.approve(CAPTAIN, claim.id()).block();

        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(players.store.get(id).getCustomerId()).isEqualTo(ARJUN.id());
        assertThat(managePlayers.get(ARJUN, id).block().mine()).isTrue();
        assertThat(managePlayers.get(CAPTAIN, id).block().phone()).as("the creator still owns the phone number").isNotNull();
        assertThat(managePlayers.get(ARJUN, id).block().phone()).isNull();
        assertThat(claims.incoming(CAPTAIN).collectList().block()).isEmpty();
    }

    @Test
    void approvingOneClaimRejectsTheOtherPendingClaimsOnTheSameGuest() {
        var id = guest(CAPTAIN, "Arjun Kumar", "9876543210");
        var winner = claims.request(ARJUN, id).block();
        var loser = claims.request(STRANGER, id).block();

        claims.approve(CAPTAIN, winner.id()).block();

        assertThat(claimStore.store.get(loser.id()).getStatus().name()).isEqualTo("REJECTED");
        assertThat(claimStore.store.get(loser.id()).getDecidedBy()).isEqualTo(CAPTAIN.id());
    }

    @Test
    void onlyTheCreatorOrAnAdministratorCanDecideAndEveryoneElseSeesNotFound() {
        var id = guest(CAPTAIN, "Arjun Kumar", "9876543210");
        var claim = claims.request(ARJUN, id).block();

        assertCode(() -> claims.approve(STRANGER, claim.id()).block(), CricketErrorCode.CLAIM_NOT_FOUND);
        assertCode(() -> claims.reject(ARJUN, claim.id()).block(), CricketErrorCode.CLAIM_NOT_FOUND);
        assertCode(() -> claims.approve(CAPTAIN, UUID.randomUUID()).block(), CricketErrorCode.CLAIM_NOT_FOUND);
        assertThat(players.store.get(id).isLinked()).isFalse();

        assertThat(claims.incoming(ADMIN).collectList().block()).hasSize(1);
        assertThat(claims.approve(ADMIN, claim.id()).block().status()).isEqualTo("APPROVED");
    }

    @Test
    void aRejectedClaimCanBeAskedAgainButADecidedOneCannotBeDecidedTwice() {
        var id = guest(CAPTAIN, "Arjun Kumar", "9876543210");
        var claim = claims.request(ARJUN, id).block();

        claims.reject(CAPTAIN, claim.id()).block();

        assertCode(() -> claims.approve(CAPTAIN, claim.id()).block(), CricketErrorCode.CLAIM_NOT_PENDING);
        assertThat(claims.request(ARJUN, id).block().status()).isEqualTo("PENDING");
        assertThat(claims.mine(ARJUN).collectList().block()).extracting(c -> c.status()).containsExactlyInAnyOrder("PENDING", "REJECTED");
    }

    @Test
    void theSameClaimCannotBeAskedTwiceWhilePending() {
        var id = guest(CAPTAIN, "Arjun Kumar", "9876543210");
        claims.request(ARJUN, id).block();

        assertCode(() -> claims.request(ARJUN, id).block(), CricketErrorCode.CLAIM_ALREADY_PENDING);
    }

    @Test
    void aLinkedProfileCannotBeClaimedAndAnAccountWithAProfileCannotClaimAnother() {
        var linked = managePlayers.createOwn(ARJUN, details("Arjun", null)).block();
        var other = guest(CAPTAIN, "Someone", null);

        assertCode(() -> claims.request(STRANGER, linked.id()).block(), CricketErrorCode.PLAYER_ALREADY_LINKED);
        assertCode(() -> claims.request(ARJUN, other).block(), CricketErrorCode.CLAIMANT_ALREADY_LINKED);
        assertCode(() -> claims.request(STRANGER, UUID.randomUUID()).block(), CricketErrorCode.PLAYER_NOT_FOUND);
    }

    @Test
    void aClaimCannotBeApprovedIfTheClaimantGotAProfileInTheMeantime() {
        var id = guest(CAPTAIN, "Arjun Kumar", "9876543210");
        var claim = claims.request(ARJUN, id).block();
        managePlayers.createOwn(ARJUN, details("Arjun made his own", null)).block();

        assertCode(() -> claims.approve(CAPTAIN, claim.id()).block(), CricketErrorCode.CLAIMANT_ALREADY_LINKED);
        assertThat(players.store.get(id).isLinked()).isFalse();
        assertThat(claimStore.store.get(claim.id()).isPending()).isTrue();
    }
}
