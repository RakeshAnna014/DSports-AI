package com.dsports.cricket.domain;

import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.BallType;
import com.dsports.cricket.domain.model.Match;
import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.model.MatchStatus;
import com.dsports.cricket.domain.model.TossDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final UUID CREATOR = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID TEAM_A = UUID.randomUUID();
    private static final UUID TEAM_B = UUID.randomUUID();

    private static Match.Settings settings(int overs) {
        return new Match.Settings(overs, BallType.TENNIS, MatchRules.standard(), NOW.plusSeconds(86400), " Chennai ", " Ravi, Sam ");
    }

    private static Match match() {
        return Match.create(CREATOR, TEAM_A, TEAM_B, settings(20), null, NOW);
    }

    private static List<UUID> players(int n) {
        return IntStream.range(0, n).mapToObj(i -> UUID.randomUUID()).toList();
    }

    private static void assertCode(Runnable action, CricketErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CricketDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    // ---- creation and settings --------------------------------------------------------------------------------

    @Test
    void aNewMatchIsScheduledWithTrimmedSettingsAndStandardRulesByDefault() {
        var m = match();

        assertThat(m.getStatus()).isEqualTo(MatchStatus.SCHEDULED);
        assertThat(m.getSettings().city()).isEqualTo("Chennai");
        assertThat(m.getSettings().umpires()).isEqualTo("Ravi, Sam");
        assertThat(m.getSettings().rules()).isEqualTo(MatchRules.standard());
        assertThat(m.getTossWinnerTeamId()).isNull();
        assertThat(m.battingFirstTeamId()).isNull();
        assertThat(m.readyToStart()).isFalse();
    }

    @Test
    void aMatchNeedsTwoDifferentTeamsAndSaneSettings() {
        assertCode(() -> Match.create(CREATOR, TEAM_A, TEAM_A, settings(20), null, NOW), CricketErrorCode.INVALID_TEAMS);
        assertCode(() -> Match.create(CREATOR, null, TEAM_B, settings(20), null, NOW), CricketErrorCode.VALIDATION_ERROR);
        assertCode(() -> Match.create(CREATOR, TEAM_A, TEAM_B, settings(0), null, NOW), CricketErrorCode.VALIDATION_ERROR);
        assertCode(() -> Match.create(CREATOR, TEAM_A, TEAM_B, settings(101), null, NOW), CricketErrorCode.VALIDATION_ERROR);
        assertCode(() -> Match.create(CREATOR, TEAM_A, TEAM_B, new Match.Settings(20, null, null, null, null, null), null, NOW), CricketErrorCode.VALIDATION_ERROR);
        assertCode(() -> Match.create(CREATOR, TEAM_A, TEAM_B, new Match.Settings(20, BallType.LEATHER, null, null, "c".repeat(81), null), null, NOW), CricketErrorCode.VALIDATION_ERROR);
    }

    @Test
    void rulesAreValidatedAgainstTheMatchLength() {
        assertCode(() -> rulesMatch(new MatchRules(3, 11, null, 1, 1, true, false), 20), CricketErrorCode.VALIDATION_ERROR);
        assertCode(() -> rulesMatch(new MatchRules(11, 11, null, 1, 1, true, false), 20), CricketErrorCode.VALIDATION_ERROR);
        assertCode(() -> rulesMatch(new MatchRules(6, 1, null, 1, 1, true, false), 20), CricketErrorCode.VALIDATION_ERROR);
        assertCode(() -> rulesMatch(new MatchRules(6, 12, null, 1, 1, true, false), 20), CricketErrorCode.VALIDATION_ERROR);
        assertCode(() -> rulesMatch(new MatchRules(6, 11, 0, 1, 1, true, false), 20), CricketErrorCode.VALIDATION_ERROR);
        assertCode(() -> rulesMatch(new MatchRules(6, 11, 21, 1, 1, true, false), 20), CricketErrorCode.VALIDATION_ERROR);
        assertCode(() -> rulesMatch(new MatchRules(6, 11, null, 6, 1, true, false), 20), CricketErrorCode.VALIDATION_ERROR);
        assertCode(() -> rulesMatch(new MatchRules(6, 11, null, 1, -1, true, false), 20), CricketErrorCode.VALIDATION_ERROR);
        assertThat(rulesMatch(new MatchRules(8, 8, 4, 2, 2, false, true), 20).getSettings().rules().playersPerSide()).isEqualTo(8);
    }

    private static Match rulesMatch(MatchRules rules, int overs) {
        return Match.create(CREATOR, TEAM_A, TEAM_B, new Match.Settings(overs, BallType.LEATHER, rules, null, null, null), null, NOW);
    }

    @Test
    void theBowlerLimitIsAFifthOfTheMatchRoundedUpUnlessConfigured() {
        var standard = MatchRules.standard();

        assertThat(standard.bowlerLimit(20)).isEqualTo(4);
        assertThat(standard.bowlerLimit(50)).isEqualTo(10);
        assertThat(standard.bowlerLimit(6)).isEqualTo(2);
        assertThat(standard.bowlerLimit(1)).isEqualTo(1);
        assertThat(new MatchRules(6, 11, 3, 1, 1, true, false).bowlerLimit(20)).isEqualTo(3);
    }

    @Test
    void theVenueCityFillsAnEmptyCityButAnExplicitCityWins() {
        var venue = new Match.Venue(UUID.randomUUID(), "Green Park", "Pune");

        var filled = Match.create(CREATOR, TEAM_A, TEAM_B, new Match.Settings(10, BallType.LEATHER, null, null, null, null), venue, NOW);
        var explicit = Match.create(CREATOR, TEAM_A, TEAM_B, new Match.Settings(10, BallType.LEATHER, null, null, "Goa", null), venue, NOW);

        assertThat(filled.getSettings().city()).isEqualTo("Pune");
        assertThat(explicit.getSettings().city()).isEqualTo("Goa");
    }

    @Test
    void settingsCanChangeBeforeTheStartButNotShrinkBelowAnExistingXi() {
        var m = match();
        var a = players(8);
        m.setPlayingXi(TEAM_A, a, Set.copyOf(a), NOW);

        m.updateSettings(settings(10), NOW.plusSeconds(1));
        assertThat(m.getSettings().overs()).isEqualTo(10);
        assertCode(() -> m.updateSettings(new Match.Settings(10, BallType.TENNIS, new MatchRules(6, 7, null, 1, 1, true, false), null, null, null), NOW), CricketErrorCode.VALIDATION_ERROR);
    }

    // ---- toss ------------------------------------------------------------------------------------------------

    @Test
    void theTossMovesTheMatchToTossDoneAndDecidesWhoBatsFirst() {
        var m = match();

        m.recordToss(TEAM_B, TossDecision.BAT, NOW);
        assertThat(m.getStatus()).isEqualTo(MatchStatus.TOSS_DONE);
        assertThat(m.battingFirstTeamId()).isEqualTo(TEAM_B);

        m.recordToss(TEAM_B, TossDecision.BOWL, NOW);
        assertThat(m.battingFirstTeamId()).isEqualTo(TEAM_A);
        m.recordToss(TEAM_A, TossDecision.BOWL, NOW);
        assertThat(m.battingFirstTeamId()).isEqualTo(TEAM_B);
        m.recordToss(TEAM_A, TossDecision.BAT, NOW);
        assertThat(m.battingFirstTeamId()).isEqualTo(TEAM_A);
    }

    @Test
    void theTossWinnerMustBeOneOfTheTeamsAndADecisionIsRequired() {
        var m = match();

        assertCode(() -> m.recordToss(UUID.randomUUID(), TossDecision.BAT, NOW), CricketErrorCode.INVALID_TOSS);
        assertCode(() -> m.recordToss(null, TossDecision.BAT, NOW), CricketErrorCode.INVALID_TOSS);
        assertCode(() -> m.recordToss(TEAM_A, null, NOW), CricketErrorCode.VALIDATION_ERROR);
        assertThat(m.getStatus()).isEqualTo(MatchStatus.SCHEDULED);
    }

    // ---- playing XIs -----------------------------------------------------------------------------------------

    @Test
    void aPlayingXiKeepsItsBattingOrderAndEachSideIsIndependent() {
        var m = match();
        var a = players(11);
        var b = players(5);

        m.setPlayingXi(TEAM_A, a, Set.copyOf(a), NOW);
        m.setPlayingXi(TEAM_B, b, Set.copyOf(b), NOW);

        assertThat(m.playingXi(TEAM_A)).containsExactlyElementsOf(a);
        assertThat(m.playingXi(TEAM_B)).containsExactlyElementsOf(b);
        var reordered = new java.util.ArrayList<>(a);
        java.util.Collections.reverse(reordered);
        m.setPlayingXi(TEAM_A, reordered, Set.copyOf(a), NOW);
        assertThat(m.playingXi(TEAM_A)).containsExactlyElementsOf(reordered);
    }

    @Test
    void anInvalidXiIsRejectedAndTheOldOneKept() {
        var m = match();
        var a = players(11);
        m.setPlayingXi(TEAM_A, a, Set.copyOf(a), NOW);
        var members = Set.copyOf(a);

        assertCode(() -> m.setPlayingXi(TEAM_A, List.of(a.get(0)), members, NOW), CricketErrorCode.INVALID_PLAYING_XI);
        assertCode(() -> m.setPlayingXi(TEAM_A, List.of(a.get(0), a.get(0)), members, NOW), CricketErrorCode.INVALID_PLAYING_XI);
        assertCode(() -> m.setPlayingXi(TEAM_A, List.of(a.get(0), UUID.randomUUID()), members, NOW), CricketErrorCode.INVALID_PLAYING_XI);
        assertCode(() -> m.setPlayingXi(TEAM_A, players(12), Set.of(), NOW), CricketErrorCode.INVALID_PLAYING_XI);
        assertCode(() -> m.setPlayingXi(UUID.randomUUID(), a, members, NOW), CricketErrorCode.INVALID_TEAMS);
        assertThat(m.playingXi(TEAM_A)).containsExactlyElementsOf(a);
    }

    @Test
    void aSidesXiCannotExceedPlayersPerSide() {
        var m = Match.create(CREATOR, TEAM_A, TEAM_B, new Match.Settings(10, BallType.TENNIS, new MatchRules(6, 8, null, 1, 1, true, false), null, null, null), null, NOW);
        var nine = players(9);

        assertCode(() -> m.setPlayingXi(TEAM_A, nine, Set.copyOf(nine), NOW), CricketErrorCode.INVALID_PLAYING_XI);
        var eight = nine.subList(0, 8);
        m.setPlayingXi(TEAM_A, eight, Set.copyOf(eight), NOW);
        assertThat(m.playingXi(TEAM_A)).hasSize(8);
    }

    @Test
    void aPlayerCannotPlayForBothSides() {
        var m = match();
        var shared = UUID.randomUUID();
        var a = List.of(shared, UUID.randomUUID());
        var b = List.of(shared, UUID.randomUUID());
        m.setPlayingXi(TEAM_A, a, Set.copyOf(a), NOW);

        assertCode(() -> m.setPlayingXi(TEAM_B, b, Set.copyOf(b), NOW), CricketErrorCode.INVALID_PLAYING_XI);
        assertThat(m.playingXi(TEAM_B)).isEmpty();
    }

    @Test
    void theMatchIsReadyOnlyWithATossAndTwoRealXis() {
        var m = match();
        var a = players(2);
        var b = players(2);

        m.setPlayingXi(TEAM_A, a, Set.copyOf(a), NOW);
        m.setPlayingXi(TEAM_B, b, Set.copyOf(b), NOW);
        assertThat(m.readyToStart()).as("no toss yet").isFalse();
        m.recordToss(TEAM_A, TossDecision.BAT, NOW);
        assertThat(m.readyToStart()).isTrue();
    }

    // ---- scorers ---------------------------------------------------------------------------------------------

    @Test
    void theCreatorAlwaysScoresAndOthersAreAddedAndRemovedOnce() {
        var m = match();
        var helper = UUID.randomUUID();

        assertThat(m.isScorer(CREATOR)).isTrue();
        assertThat(m.isScorer(helper)).isFalse();
        m.addScorer(helper, NOW);
        assertThat(m.isScorer(helper)).isTrue();
        assertCode(() -> m.addScorer(helper, NOW), CricketErrorCode.SCORER_ALREADY_ADDED);
        assertCode(() -> m.addScorer(CREATOR, NOW), CricketErrorCode.SCORER_ALREADY_ADDED);
        m.removeScorer(helper, NOW);
        assertThat(m.isScorer(helper)).isFalse();
        assertCode(() -> m.removeScorer(helper, NOW), CricketErrorCode.NOT_A_SCORER);
        assertCode(() -> m.removeScorer(CREATOR, NOW), CricketErrorCode.NOT_A_SCORER);
    }

    // ---- once started ----------------------------------------------------------------------------------------

    @Test
    void onceTheMatchHasStartedItsSetupIsLocked() {
        var started = Match.reconstitute(UUID.randomUUID(), TEAM_A, TEAM_B, settings(20), null, MatchStatus.IN_PROGRESS, TEAM_A, TossDecision.BAT,
            players(11), players(11), Set.of(), CREATOR, 3, NOW, NOW);
        var a = players(2);

        assertCode(() -> started.recordToss(TEAM_B, TossDecision.BOWL, NOW), CricketErrorCode.MATCH_STARTED);
        assertCode(() -> started.setPlayingXi(TEAM_A, a, Set.copyOf(a), NOW), CricketErrorCode.MATCH_STARTED);
        assertCode(() -> started.updateSettings(settings(10), NOW), CricketErrorCode.MATCH_STARTED);
    }
}
