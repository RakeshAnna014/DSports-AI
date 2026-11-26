package com.dsports.cricket.domain;

import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.ClaimStatus;
import com.dsports.cricket.domain.model.PlayerClaim;
import com.dsports.cricket.domain.model.Team;
import com.dsports.cricket.domain.model.TeamStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TeamAndClaimTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static Team team() {
        return Team.create(OWNER, "  Green Lions ", " Chennai ", NOW);
    }

    private static void assertCode(Runnable action, CricketErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CricketDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void aNewTeamIsActiveTrimmedAndEmpty() {
        var team = team();

        assertThat(team.getName()).isEqualTo("Green Lions");
        assertThat(team.getCity()).isEqualTo("Chennai");
        assertThat(team.getStatus()).isEqualTo(TeamStatus.ACTIVE);
        assertThat(team.getMembers()).isEmpty();
        assertThat(team.captainId()).isEmpty();
    }

    @Test
    void aTeamNeedsANameAndTheCityIsOptional() {
        assertCode(() -> Team.create(OWNER, " ", null, NOW), CricketErrorCode.VALIDATION_ERROR);
        assertCode(() -> Team.create(OWNER, "x".repeat(61), null, NOW), CricketErrorCode.VALIDATION_ERROR);
        assertCode(() -> Team.create(OWNER, "A", "c".repeat(81), NOW), CricketErrorCode.VALIDATION_ERROR);
        assertThat(Team.create(OWNER, "A", "  ", NOW).getCity()).isNull();
    }

    @Test
    void membersAreAddedOnceAndNeverBeyondTheLimit() {
        var team = team();
        var first = UUID.randomUUID();
        team.addMember(first, NOW);

        assertCode(() -> team.addMember(first, NOW), CricketErrorCode.ALREADY_A_MEMBER);
        IntStream.range(1, Team.MAX_MEMBERS).forEach(i -> team.addMember(UUID.randomUUID(), NOW));
        assertThat(team.getMembers()).hasSize(Team.MAX_MEMBERS);
        assertCode(() -> team.addMember(UUID.randomUUID(), NOW), CricketErrorCode.TEAM_FULL);
    }

    @Test
    void thereIsAtMostOneCaptainAndTheyMustBeAMember() {
        var team = team();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        team.addMember(a, NOW);
        team.addMember(b, NOW);

        assertCode(() -> team.setCaptain(UUID.randomUUID(), NOW), CricketErrorCode.NOT_A_MEMBER);
        team.setCaptain(a, NOW);
        team.setCaptain(b, NOW);

        assertThat(team.captainId()).contains(b);
        assertThat(team.getMembers().stream().filter(Team.Member::captain)).hasSize(1);
    }

    @Test
    void theCaptainCannotBeRemovedUntilAnotherIsChosen() {
        var team = team();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        team.addMember(a, NOW);
        team.addMember(b, NOW);
        team.setCaptain(a, NOW);

        assertCode(() -> team.removeMember(a, NOW), CricketErrorCode.CANNOT_REMOVE_CAPTAIN);
        team.removeMember(b, NOW);
        assertCode(() -> team.removeMember(b, NOW), CricketErrorCode.NOT_A_MEMBER);
        team.addMember(b, NOW);
        team.setCaptain(b, NOW);
        team.removeMember(a, NOW);

        assertThat(team.isMember(a)).isFalse();
        assertThat(team.captainId()).contains(b);
    }

    @Test
    void anArchivedTeamCannotChangeAnyMore() {
        var team = team();
        var a = UUID.randomUUID();
        team.addMember(a, NOW);
        team.archive(NOW);

        assertThat(team.getStatus()).isEqualTo(TeamStatus.ARCHIVED);
        assertCode(() -> team.update("New", null, NOW), CricketErrorCode.TEAM_ARCHIVED);
        assertCode(() -> team.addMember(UUID.randomUUID(), NOW), CricketErrorCode.TEAM_ARCHIVED);
        assertCode(() -> team.removeMember(a, NOW), CricketErrorCode.TEAM_ARCHIVED);
        assertCode(() -> team.setCaptain(a, NOW), CricketErrorCode.TEAM_ARCHIVED);
        assertCode(() -> team.archive(NOW), CricketErrorCode.TEAM_ARCHIVED);
    }

    @Test
    void renamingRevalidates() {
        var team = team();

        team.update("Lions", "Pune", NOW.plusSeconds(1));

        assertThat(team.getName()).isEqualTo("Lions");
        assertThat(team.getCity()).isEqualTo("Pune");
        assertCode(() -> team.update("", "Pune", NOW), CricketErrorCode.VALIDATION_ERROR);
    }

    // ---- claims ----------------------------------------------------------------------------------------------

    @Test
    void aClaimStartsPendingAndIsDecidedExactlyOnce() {
        var claim = PlayerClaim.request(UUID.randomUUID(), UUID.randomUUID(), NOW);
        var decider = UUID.randomUUID();

        assertThat(claim.isPending()).isTrue();
        assertThat(claim.getDecidedAt()).isNull();
        claim.approve(decider, NOW.plusSeconds(60));

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(claim.getDecidedBy()).isEqualTo(decider);
        assertThat(claim.getDecidedAt()).isEqualTo(NOW.plusSeconds(60));
        assertCode(() -> claim.reject(decider, NOW), CricketErrorCode.CLAIM_NOT_PENDING);
        assertCode(() -> claim.approve(decider, NOW), CricketErrorCode.CLAIM_NOT_PENDING);
    }

    @Test
    void aRejectedClaimStaysRejected() {
        var claim = PlayerClaim.request(UUID.randomUUID(), UUID.randomUUID(), NOW);

        claim.reject(UUID.randomUUID(), NOW);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REJECTED);
        assertCode(() -> claim.approve(UUID.randomUUID(), NOW), CricketErrorCode.CLAIM_NOT_PENDING);
    }
}
