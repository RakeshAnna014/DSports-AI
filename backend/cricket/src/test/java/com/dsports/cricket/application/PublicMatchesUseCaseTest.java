package com.dsports.cricket.application;

import com.dsports.cricket.application.result.PublicMatchSummary;
import com.dsports.cricket.application.usecase.PublicMatchesUseCase;
import com.dsports.cricket.application.usecase.PublicScorecardEtag;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.BallType;
import com.dsports.cricket.domain.model.Match;
import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.model.MatchStatus;
import com.dsports.cricket.domain.model.Player;
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
        assertNotFound(() -> publicMatches.scorecard(UUID.randomUUID()).block());
    }

    @Test
    void aMatchAbandonedBeforeABallWasBowledNeverStartedSoItIsNotPublic() {
        fx.score(new ScoreCommand.EndMatch(ScoreCommand.EndMatch.Reason.ABANDONED));
        assertNotFound(() -> publicMatches.scorecard(fx.id()).block());
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
        var card = publicMatches.scorecard(fx.id()).block();
        var json = new ObjectMapper().findAndRegisterModules().writeValueAsString(card);

        var playerIds = card.innings().stream().flatMap(i -> java.util.stream.Stream.concat(i.batters().stream().map(x -> x.playerId()), i.bowlers().stream().map(x -> x.playerId()))).distinct()
            .map(UUID::toString).toList();
        assertThat(playerIds).as("a batter or bowler carries a player id, to link to that player's career").isNotEmpty();
        var uuids = UUID_TEXT.matcher(json).results().map(r -> r.group()).distinct().toList();
        assertThat(uuids).as("the only ids in a public scorecard are the match's own and the players on the card (no team, user or creator id)")
            .containsExactlyInAnyOrderElementsOf(java.util.stream.Stream.concat(java.util.stream.Stream.of(fx.id().toString()), playerIds.stream()).toList());
        assertThat(json).doesNotContain("@", "email", "phone", "booking", "claim", "scorer", "userId", "createdBy", "version", "editableUntil", "canUndo", "recentEvents", "eligible");
        assertThat(json).doesNotContain("Olive", "Sam Scorer");
    }

    // ---- the ETag -------------------------------------------------------------------------------------------------

    @Test
    void theEtagStartsWithTheSeqAndMovesOnEveryChangeIncludingAnUndo() {
        fx.scoreAll(GoldenMatch.lionsInnings().subList(0, 3));
        var first = etag();
        assertThat(first).startsWith("\"3-").endsWith("\"");
        assertThat(etag()).as("nothing scored, nothing changed").isEqualTo(first);
        assertThat(publicMatches.scorecard(fx.id()).block().seq()).isEqualTo(3);

        fx.score(GoldenMatch.lionsInnings().get(3));
        var second = etag();
        assertThat(second).startsWith("\"4-").isNotEqualTo(first);

        var before = publicMatches.scorecard(fx.id()).block().innings().get(0).runs();
        fx.undo(ScoringLog.UndoMode.BALL);
        var after = publicMatches.scorecard(fx.id()).block();
        assertThat(after.seq()).as("the undo is an event of its own").isEqualTo(5);
        assertThat(PublicScorecardEtag.of(after)).startsWith("\"5-").isNotEqualTo(second);
        assertThat(after.innings().get(0).runs()).isLessThan(before);
    }

    @Test
    void renamingAPlayerOrATeamChangesTheEtagEvenThoughNoBallWasScored() {
        fx.scoreAll(GoldenMatch.lionsInnings().subList(0, 3));
        var before = etag();
        var seq = publicMatches.scorecard(fx.id()).block().seq();

        var batter = fx.players.store.get(GoldenMatch.lion(1));
        batter.update(new Player.Details("Arun Kumar", null, null, null, null, null), ScoringFixture.T0);
        var renamed = publicMatches.scorecard(fx.id()).block();
        assertThat(renamed.seq()).as("no event was scored").isEqualTo(seq);
        assertThat(renamed.innings().get(0).batters().get(0).name()).isEqualTo("Arun Kumar");
        var afterPlayer = PublicScorecardEtag.of(renamed);
        assertThat(afterPlayer).as("same seq, different tag").startsWith("\"" + seq + "-").isNotEqualTo(before);

        var team = fx.teams.store.get(GoldenMatch.LIONS);
        team.update("Lions XI", team.getCity(), ScoringFixture.T0);
        var afterTeam = PublicScorecardEtag.of(publicMatches.scorecard(fx.id()).block());
        assertThat(afterTeam).isNotEqualTo(afterPlayer);
    }

    @Test
    void theEtagIsTheSameForTheSameScorecardWhoeverAsksAndWhenever() {
        fx.scoreAll(GoldenMatch.lionsInnings());
        assertThat(etag()).isEqualTo(etag());
        assertThat(PublicScorecardEtag.of(publicMatches.scorecard(fx.id()).block())).matches("\"\\d+-[0-9a-f]{16}\"");
    }

    private String etag() {
        return PublicScorecardEtag.of(publicMatches.scorecard(fx.id()).block());
    }

    @Test
    void aLostSnapshotDoesNotHideAMatchOrChangeItsScorecard() {
        fx.scoreAll(GoldenMatch.lionsInnings());
        var with = publicMatches.scorecard(fx.id()).block();
        fx.store.snapshots.remove(fx.id());

        assertThat(publicMatches.scorecard(fx.id()).block()).isEqualTo(with);
        assertThat(etag()).isEqualTo(PublicScorecardEtag.of(with));
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

        var page = publicMatches.list(null, 0, 20).block();

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

        assertThat(publicMatches.list("  CHENNAI ", 0, 20).block().items()).extracting(PublicMatchSummary::matchId).containsExactly(chennai.getId());
        assertThat(publicMatches.list("chennai", 0, 20).block().items()).hasSize(1);
        assertThat(publicMatches.list("", 0, 20).block().items()).as("a blank city means every city").hasSize(3);
        assertThat(publicMatches.list("Delhi", 0, 20).block().items()).isEmpty();
    }

    @Test
    void theListIsPaginatedAndSaysWhetherAnotherPageFollows() {
        for (int i = 0; i < 5; i++) {
            liveMatch("Chennai", MatchStatus.IN_PROGRESS, 5, i);
        }
        var first = publicMatches.list(null, 0, 2).block();
        var second = publicMatches.list(null, 1, 2).block();
        var third = publicMatches.list(null, 2, 2).block();

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
        assertThat(publicMatches.list(null, 3, 2).block().items()).isEmpty();
    }

    @Test
    void anOutOfRangePageOrSizeOrALongCityIsRefusedNotAnError() {
        for (var args : List.<int[]>of(new int[] {-1, 20}, new int[] {0, 0}, new int[] {0, 51}, new int[] {10_001, 20})) {
            assertThatThrownBy(() -> publicMatches.list(null, args[0], args[1]).block()).as(java.util.Arrays.toString(args))
                .isInstanceOfSatisfying(CricketDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(CricketErrorCode.VALIDATION_ERROR));
        }
        assertThatThrownBy(() -> publicMatches.list("x".repeat(81), 0, 20).block()).isInstanceOf(CricketDomainException.class);
    }

    @Test
    void theListCarriesNoIdsButTheMatchesOwn() throws Exception {
        liveMatch("Chennai", MatchStatus.IN_PROGRESS, 8, 1);
        var json = new ObjectMapper().findAndRegisterModules().writeValueAsString(publicMatches.list(null, 0, 20).block());

        assertThat(UUID_TEXT.matcher(json).results().count()).isEqualTo(1);
        assertThat(json).doesNotContain("@", "createdBy", "scorer", "booking");
    }

    // ---- the analytics (charts and commentary) ------------------------------------------------------------------------

    @Test
    void theAnalyticsOfAMatchThatHasNotStartedAreNotFoundExactlyLikeOneThatDoesNotExist() {
        assertNotFound(() -> publicMatches.analytics(fx.id()).block());
        assertNotFound(() -> publicMatches.analytics(UUID.randomUUID()).block());
    }

    @Test
    void theAnalyticsFollowTheLogWithNamesAndNumbersOnly() {
        fx.scoreAll(GoldenMatch.wholeMatch());
        var result = publicMatches.analytics(fx.id()).block();
        assertThat(result.seq()).isEqualTo(GoldenMatch.wholeMatch().size());
        assertThat(result.innings()).hasSize(2);
        assertThat(result.innings().get(0).overRuns()).extracting(o -> o.runs()).containsExactly(11, 11);
        assertThat(result.innings().get(0).partnerships().get(0).batterA()).isEqualTo("Lion 1");
        assertThat(result.innings().get(1).worm().get(result.innings().get(1).worm().size() - 1).runs()).isEqualTo(23);
        assertThat(result.commentary()).as("one line for each ball of both innings").hasSize(27);
        assertThat(result.commentary().get(0).text()).isEqualTo("Tiger 1: wide");
        assertThat(result.toString()).as("no id of a player, a team or a person, and no email").doesNotContainPattern(UUID_TEXT.pattern()).doesNotContain("@");
    }

    @Test
    void anUndoChangesTheAnalyticsAndTheTagMovesWithEveryBallAndNoBallStaysStill() {
        fx.score(ScoringFixture.lionsOpen());
        fx.score(new ScoreCommand.Play(new com.dsports.cricket.domain.rules.ScoringCommand.Deliver(com.dsports.cricket.domain.rules.Delivery.runs(3))));
        var tag = com.dsports.cricket.application.usecase.PublicScorecardEtag.of(publicMatches.analytics(fx.id()).block().seq(), publicMatches.analytics(fx.id()).block());
        assertThat(com.dsports.cricket.application.usecase.PublicScorecardEtag.of(publicMatches.analytics(fx.id()).block().seq(), publicMatches.analytics(fx.id()).block()))
            .as("nothing scored, the same tag").isEqualTo(tag);
        fx.undo(com.dsports.cricket.domain.scoring.ScoringLog.UndoMode.BALL);
        var after = publicMatches.analytics(fx.id()).block();
        assertThat(after.innings().get(0).runs()).isZero();
        assertThat(after.commentary()).isEmpty();
        assertThat(com.dsports.cricket.application.usecase.PublicScorecardEtag.of(after.seq(), after)).isNotEqualTo(tag);
    }
}
