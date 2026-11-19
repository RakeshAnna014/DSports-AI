package com.dsports.cricket.application;

import com.dsports.cricket.application.result.PublicMatchSummary;
import com.dsports.cricket.application.usecase.PublicMatchesUseCase;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.BallType;
import com.dsports.cricket.domain.model.Match;
import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.model.MatchStatus;
import com.dsports.cricket.domain.scoring.EditWindow;
import com.dsports.cricket.domain.scoring.GoldenMatch;
import com.dsports.cricket.domain.scoring.MatchScoring;
import com.dsports.cricket.domain.scoring.ScoreCommand;
import com.dsports.cricket.domain.scoring.ScoringLog;
import com.dsports.cricket.application.port.ScoringStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The public side of scoring: what a stranger may read. The scorecard exists only once the first innings has started (before that it is "not found", the same as a match
 * that does not exist), carries names and numbers and nothing else, follows every ball and undo, and survives a lost snapshot. The list shows only matches being played.
 */
class PublicMatchesUseCaseTest {

    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final ScoringFixture fx = new ScoringFixture();
    private final PublicMatchesUseCase publicMatches = new PublicMatchesUseCase(fx.matches, fx.teams, fx.players, fx.store);

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CricketDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(CricketErrorCode.MATCH_NOT_FOUND));
    }

    // ---- the scorecard --------------------------------------------------------------------------------------------

    @Test
    void aMatchThatHasNotStartedIsNotFoundExactlyLikeOneThatDoesNotExist() {
        assertNotFound(() -> publicMatches.scorecard(fx.id()).block());
        assertNotFound(() -> publicMatches.version(fx.id()).block());
        assertNotFound(() -> publicMatches.scorecard(UUID.randomUUID()).block());
        assertNotFound(() -> publicMatches.version(UUID.randomUUID()).block());
    }

    @Test
    void aMatchAbandonedBeforeABallWasBowledNeverStartedSoItIsNotPublic() {
        fx.score(new ScoreCommand.EndMatch(ScoreCommand.EndMatch.Reason.ABANDONED));
        assertNotFound(() -> publicMatches.scorecard(fx.id()).block());
        assertNotFound(() -> publicMatches.version(fx.id()).block());
    }

    @Test
    void theScorecardShowsTheInningsWithNamesAndDismissalsAndTheRunRate() {
        fx.scoreAll(GoldenMatch.lionsInnings());
        var card = publicMatches.scorecard(fx.id()).block();

        assertThat(card.matchId()).isEqualTo(fx.id());
        assertThat(card.seq()).isEqualTo(GoldenMatch.lionsInnings().size());
        assertThat(card.teamA()).isEqualTo("Lions");
        assertThat(card.teamB()).isEqualTo("Tigers");
        assertThat(card.status()).isEqualTo("INNINGS_BREAK");
        assertThat(card.live()).isTrue();
        assertThat(card.result()).isNull();
        var innings = card.innings().get(0);
        assertThat(innings.battingTeam()).isEqualTo("Lions");
        assertThat(innings.runs()).isEqualTo(22);
        assertThat(innings.wickets()).isEqualTo(2);
        assertThat(innings.overs()).isEqualTo("2.0");
        assertThat(innings.runRate()).isEqualByComparingTo(new BigDecimal("11.00"));
        assertThat(innings.requiredRate()).isNull();
        assertThat(innings.batters().get(0).name()).isEqualTo("Lion 1");
        assertThat(innings.batters().get(0).howOut()).isEqualTo("run out (Tiger 3)");
        assertThat(innings.batters().get(2).howOut()).isEqualTo("b Tiger 1");
        assertThat(innings.batters()).noneMatch(b -> b.atCrease() || b.striker());
        assertThat(innings.fallOfWickets()).hasSize(2);
        assertThat(innings.bowlers()).isNotEmpty();
        assertThat(innings.extras().total()).isEqualTo(innings.extras().wides() + innings.extras().noBalls() + innings.extras().byes() + innings.extras().legByes());
    }

    @Test
    void aChaseShowsTheTargetAndTheRateRequired() {
        fx.scoreAll(GoldenMatch.lionsInnings());
        fx.scoreAll(GoldenMatch.tigersChase().subList(0, 3));
        var card = publicMatches.scorecard(fx.id()).block();

        assertThat(card.status()).isEqualTo("IN_PROGRESS");
        var chase = card.innings().get(1);
        assertThat(chase.battingTeam()).isEqualTo("Tigers");
        assertThat(chase.runs()).isEqualTo(10);
        assertThat(chase.target()).isEqualTo(23);
        assertThat(chase.runsNeeded()).isEqualTo(13);
        assertThat(chase.ballsRemaining()).isEqualTo(10);
        assertThat(chase.runRate()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(chase.requiredRate()).isEqualByComparingTo(new BigDecimal("7.80"));
        assertThat(chase.batters().stream().filter(b -> b.striker()).map(b -> b.name())).containsExactly("Tiger 1");
        assertThat(chase.batters().stream().filter(b -> b.atCrease()).count()).isEqualTo(2);
        assertThat(chase.bowlers().stream().filter(b -> b.bowling()).map(b -> b.name())).containsExactly("Lion 1");
    }

    @Test
    void aFinishedMatchHasItsResultAndNoLiveMarkers() {
        fx.scoreAll(GoldenMatch.wholeMatch());
        var card = publicMatches.scorecard(fx.id()).block();

        assertThat(card.status()).isEqualTo("COMPLETED");
        assertThat(card.live()).isFalse();
        assertThat(card.result().type()).isEqualTo("WON_BY_WICKETS");
        assertThat(card.result().winner()).isEqualTo("Tigers");
        assertThat(card.result().text()).isEqualTo("Tigers won by 10 wickets (0 balls left)");
        assertThat(card.innings().get(1).requiredRate()).isNull();
        assertThat(card.innings().get(1).batters()).noneMatch(b -> b.atCrease() || b.striker());
        assertThat(card.innings().get(1).bowlers()).noneMatch(b -> b.bowling());
    }

    @Test
    void anAbandonedMatchSaysSoAfterItStarted() {
        fx.scoreAll(GoldenMatch.lionsInnings().subList(0, 4));
        fx.score(new ScoreCommand.EndMatch(ScoreCommand.EndMatch.Reason.ABANDONED));
        var card = publicMatches.scorecard(fx.id()).block();

        assertThat(card.status()).isEqualTo("ABANDONED");
        assertThat(card.result().text()).isEqualTo("Match abandoned");
        assertThat(card.live()).isFalse();
    }

    @Test
    void nothingButNamesAndNumbersLeaves() throws Exception {
        fx.scoreAll(GoldenMatch.wholeMatch());
        var json = new ObjectMapper().findAndRegisterModules().writeValueAsString(publicMatches.scorecard(fx.id()).block());

        var uuids = UUID_TEXT.matcher(json).results().map(r -> r.group()).toList();
        assertThat(uuids).as("the match's own id is the only id in a public scorecard").containsExactly(fx.id().toString());
        assertThat(json).doesNotContain("@", "email", "phone", "booking", "claim", "scorer", "userId", "createdBy", "version", "editableUntil", "canUndo", "recentEvents", "eligible");
        assertThat(json).doesNotContain("Olive", "Sam Scorer");
    }

    // ---- the ETag -------------------------------------------------------------------------------------------------

    @Test
    void theVersionIsTheSeqOfTheLastEventAndMovesOnEveryChangeIncludingAnUndo() {
        fx.scoreAll(GoldenMatch.lionsInnings().subList(0, 3));
        assertThat(publicMatches.version(fx.id()).block()).isEqualTo(3);
        assertThat(publicMatches.version(fx.id()).block()).as("nothing scored, nothing changed").isEqualTo(3);
        assertThat(publicMatches.scorecard(fx.id()).block().seq()).isEqualTo(3);

        fx.score(GoldenMatch.lionsInnings().get(3));
        assertThat(publicMatches.version(fx.id()).block()).isEqualTo(4);

        var before = publicMatches.scorecard(fx.id()).block().innings().get(0).runs();
        fx.undo(ScoringLog.UndoMode.BALL);
        var after = publicMatches.scorecard(fx.id()).block();
        assertThat(after.seq()).as("the undo is an event of its own").isEqualTo(5);
        assertThat(publicMatches.version(fx.id()).block()).isEqualTo(5);
        assertThat(after.innings().get(0).runs()).isLessThan(before);
    }

    @Test
    void aLostSnapshotDoesNotHideAMatchOrChangeItsVersion() {
        fx.scoreAll(GoldenMatch.lionsInnings());
        var with = publicMatches.scorecard(fx.id()).block();
        fx.store.snapshots.remove(fx.id());

        assertThat(publicMatches.version(fx.id()).block()).isEqualTo(with.seq());
        assertThat(publicMatches.scorecard(fx.id()).block()).isEqualTo(with);
    }

    // ---- the live list --------------------------------------------------------------------------------------------

    /** A match between the same two teams that is being played now, with {@code balls} commands of the golden innings scored, last scored {@code minutesAgo} minutes before T0+1h. */
    private Match liveMatch(String city, MatchStatus status, int commands, int minutesAgo) {
        var id = UUID.randomUUID();
        var settings = new Match.Settings(2, BallType.TENNIS, MatchRules.standard(), null, city, null);
        var match = Match.reconstitute(id, GoldenMatch.LIONS, GoldenMatch.TIGERS, settings, null, status, GoldenMatch.LIONS, null, List.of(), List.of(), Set.of(), ScoringFixture.ORGANISER.id(), 1,
            ScoringFixture.T0, Instant.parse("2026-11-20T11:00:00Z").minusSeconds(60L * minutesAgo));
        fx.matches.store.put(id, match);
        var score = MatchScoring.fold(GoldenMatch.SETUP, GoldenMatch.lionsInnings().subList(0, commands));
        fx.store.snapshots.put(id, new ScoringStore.Snapshot(commands, score, EditWindow.open()));
        return match;
    }

    @Test
    void theListHoldsOnlyMatchesBeingPlayedMostRecentlyScoredFirst() {
        var older = liveMatch("Chennai", MatchStatus.IN_PROGRESS, 6, 30);
        var newer = liveMatch("Chennai", MatchStatus.INNINGS_BREAK, 21, 5);
        liveMatch("Chennai", MatchStatus.COMPLETED, 21, 1);
        liveMatch("Chennai", MatchStatus.TOSS_DONE, 0, 1);
        liveMatch("Chennai", MatchStatus.ABANDONED, 4, 1);

        var page = publicMatches.list("LIVE", null, 0, 20).block();

        assertThat(page.items()).extracting(PublicMatchSummary::matchId).containsExactly(newer.getId(), older.getId());
        assertThat(page.hasNext()).isFalse();
        var first = page.items().get(0);
        assertThat(first.teamA()).isEqualTo("Lions");
        assertThat(first.teamB()).isEqualTo("Tigers");
        assertThat(first.status()).isEqualTo("INNINGS_BREAK");
        assertThat(first.scores()).hasSize(1);
        assertThat(first.scores().get(0).team()).isEqualTo("Lions");
        assertThat(first.scores().get(0).runs()).isEqualTo(22);
        assertThat(first.scores().get(0).wickets()).isEqualTo(2);
        assertThat(first.scores().get(0).overs()).isEqualTo("2.0");
        assertThat(first.scores().get(0).current()).isTrue();
    }

    @Test
    void theCityFilterIgnoresLetterCaseAndSurroundingSpaces() {
        var chennai = liveMatch("Chennai", MatchStatus.IN_PROGRESS, 5, 1);
        liveMatch("Mumbai", MatchStatus.IN_PROGRESS, 5, 2);
        liveMatch(null, MatchStatus.IN_PROGRESS, 5, 3);

        assertThat(publicMatches.list("LIVE", "  CHENNAI ", 0, 20).block().items()).extracting(PublicMatchSummary::matchId).containsExactly(chennai.getId());
        assertThat(publicMatches.list("LIVE", "chennai", 0, 20).block().items()).hasSize(1);
        assertThat(publicMatches.list("LIVE", "", 0, 20).block().items()).as("a blank city means every city").hasSize(3);
        assertThat(publicMatches.list("LIVE", "Delhi", 0, 20).block().items()).isEmpty();
    }

    @Test
    void theListIsPaginatedAndSaysWhetherAnotherPageFollows() {
        for (int i = 0; i < 5; i++) {
            liveMatch("Chennai", MatchStatus.IN_PROGRESS, 5, i);
        }
        var first = publicMatches.list("LIVE", null, 0, 2).block();
        var second = publicMatches.list("LIVE", null, 1, 2).block();
        var third = publicMatches.list("LIVE", null, 2, 2).block();

        assertThat(first.items()).hasSize(2);
        assertThat(first.hasNext()).isTrue();
        assertThat(second.items()).hasSize(2);
        assertThat(second.hasNext()).isTrue();
        assertThat(third.items()).hasSize(1);
        assertThat(third.hasNext()).isFalse();
        var all = new java.util.ArrayList<UUID>();
        for (var page : List.of(first, second, third)) {
            page.items().forEach(m -> all.add(m.matchId()));
        }
        assertThat(all).as("no match twice and none missing").doesNotHaveDuplicates().hasSize(5);
        assertThat(publicMatches.list("LIVE", null, 3, 2).block().items()).isEmpty();
    }

    @Test
    void anUnknownStatusOrAnOutOfRangePageIsRefusedNotAnError() {
        for (var args : List.<Object[]>of(new Object[] {"DONE", 0, 20}, new Object[] {null, 0, 20}, new Object[] {"live", 0, 20}, new Object[] {"LIVE", -1, 20}, new Object[] {"LIVE", 0, 0},
            new Object[] {"LIVE", 0, 51}, new Object[] {"LIVE", 10_001, 20})) {
            assertThatThrownBy(() -> publicMatches.list((String) args[0], null, (int) args[1], (int) args[2]).block()).as(java.util.Arrays.toString(args))
                .isInstanceOfSatisfying(CricketDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(CricketErrorCode.VALIDATION_ERROR));
        }
        assertThatThrownBy(() -> publicMatches.list("LIVE", "x".repeat(81), 0, 20).block()).isInstanceOf(CricketDomainException.class);
    }

    @Test
    void theListCarriesNoIdsButTheMatchesOwn() throws Exception {
        liveMatch("Chennai", MatchStatus.IN_PROGRESS, 8, 1);
        var json = new ObjectMapper().findAndRegisterModules().writeValueAsString(publicMatches.list("LIVE", null, 0, 20).block());

        assertThat(UUID_TEXT.matcher(json).results().count()).isEqualTo(1);
        assertThat(json).doesNotContain("@", "createdBy", "scorer", "booking");
    }
}
