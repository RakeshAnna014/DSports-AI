package com.dsports.cricket.domain.scoring;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static com.dsports.cricket.domain.scoring.GoldenMatch.LIONS;
import static com.dsports.cricket.domain.scoring.GoldenMatch.SETUP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The charts and the commentary worked out from the log, checked on the hand-scored golden match (Lions 22/2 in two overs: 11 in the first over with a wide, a
 * no-ball four, a run-out and a bowled, 11 in the second; Tigers chase 23 and win off the last ball).
 */
class MatchAnalyticsTest {

    private static final Function<UUID, String> PLAYER = id -> {
        long group = id.getMostSignificantBits() & 0xffff;
        return (group == 0x0a ? "Lion " : group == 0x14 ? "Tiger " : "Player ") + id.getLeastSignificantBits();
    };
    private static final Function<UUID, String> TEAM = id -> id.equals(LIONS) ? "Lions" : "Tigers";

    private static MatchAnalytics.Analytics golden() {
        return MatchAnalytics.of(SETUP, GoldenMatch.log(GoldenMatch.wholeMatch()), PLAYER, TEAM);
    }

    @Test
    void runsInEachOverAddUpToTheInningsTotalAndMarkTheWickets() {
        var first = golden().innings().get(0);
        assertThat(first).extracting("battingTeam", "bowlingTeam", "runs", "wickets", "overs").containsExactly("Lions", "Tigers", 22, 2, "2.0");
        assertThat(first.overRuns()).extracting(MatchAnalytics.OverRuns::over, MatchAnalytics.OverRuns::runs, MatchAnalytics.OverRuns::wickets, MatchAnalytics.OverRuns::complete)
            .containsExactly(tuple(1, 11, 2, true), tuple(2, 11, 0, true));
        assertThat(first.overRuns().stream().mapToInt(MatchAnalytics.OverRuns::runs).sum()).isEqualTo(first.runs());
        assertThat(first.overRuns()).extracting(MatchAnalytics.OverRuns::maiden).containsOnly(false);
        assertThat(first.overRuns().get(0).bowler()).isEqualTo("Tiger 1");
    }

    @Test
    void theWormHasTheStartAndTheTotalAfterEveryOver() {
        var first = golden().innings().get(0);
        assertThat(first.worm()).extracting(MatchAnalytics.WormPoint::over, MatchAnalytics.WormPoint::legalBalls, MatchAnalytics.WormPoint::runs, MatchAnalytics.WormPoint::wickets)
            .containsExactly(tuple(0, 0, 0, 0), tuple(1, 6, 11, 2), tuple(2, 12, 22, 2));
        var second = golden().innings().get(1);
        assertThat(second.battingTeam()).isEqualTo("Tigers");
        assertThat(second.target()).isEqualTo(23);
        assertThat(second.worm().get(second.worm().size() - 1).runs()).isEqualTo(23);
    }

    @Test
    void partnershipsAreTheRunsBetweenWicketsWithTheTwoWhoWereTogether() {
        var first = golden().innings().get(0);
        assertThat(first.partnerships()).extracting(MatchAnalytics.Partnership::wicket, MatchAnalytics.Partnership::batterA, MatchAnalytics.Partnership::batterB,
                MatchAnalytics.Partnership::runs, MatchAnalytics.Partnership::balls, MatchAnalytics.Partnership::unbroken)
            .containsExactly(tuple(1, "Lion 1", "Lion 2", 11, 4, false), tuple(2, "Lion 3", "Lion 2", 0, 1, false), tuple(3, "Lion 2", "Lion 5", 11, 7, true));
        assertThat(first.partnerships().stream().mapToInt(MatchAnalytics.Partnership::runs).sum()).as("every run is in some partnership").isEqualTo(first.runs());
    }

    @Test
    void thereIsOneCommentaryLineForEachBallInWordsWithTheOverAndTheScoreAtAWicket() {
        var lines = golden().commentary();
        var first = lines.stream().filter(l -> l.innings() == 1).toList();
        assertThat(first).hasSize(14);
        assertThat(first.get(0).over()).isEqualTo("0.1");
        assertThat(first.get(0).text()).isEqualTo("Tiger 1: wide");
        assertThat(first.get(2).text()).isEqualTo("Tiger 1 to Lion 2: no ball, 4 runs; free hit next");
        assertThat(first.get(2).boundary()).isTrue();
        assertThat(first.get(2).runs()).isEqualTo(5);
        var wickets = first.stream().filter(MatchAnalytics.CommentaryLine::wicket).toList();
        assertThat(wickets).extracting(MatchAnalytics.CommentaryLine::text).containsExactly("Tiger 1 to Lion 1: 1 run. OUT! Lion 1, run out (Tiger 3) (11/1)", "Tiger 1 to Lion 3: no run. OUT! Lion 3, b Tiger 1 (11/2)");
        assertThat(first.get(first.size() - 1).text()).contains("1 run");
        assertThat(first.stream().map(MatchAnalytics.CommentaryLine::seq)).isSorted();
    }

    @Test
    void anUndoChangesWhatIsReplayedAndSoTheChartsAndTheCommentary() {
        var commands = new ArrayList<>(GoldenMatch.lionsInnings());
        var log = new ArrayList<>(GoldenMatch.log(commands));
        log.add(LogEntry.undo(log.size() + 1, List.of(log.size()), UUID.randomUUID(), UUID.randomUUID(), java.time.Instant.EPOCH));
        var after = MatchAnalytics.of(SETUP, log, PLAYER, TEAM);
        assertThat(after.innings().get(0).runs()).isEqualTo(21);
        assertThat(after.commentary()).hasSize(13);
        assertThat(after.innings().get(0).worm().get(2).runs()).isEqualTo(21);
    }

    @Test
    void aMatchWithNothingScoredHasNothing() {
        var none = MatchAnalytics.of(SETUP, List.of(), PLAYER, TEAM);
        assertThat(none.innings()).isEmpty();
        assertThat(none.commentary()).isEmpty();
        var started = MatchAnalytics.of(SETUP, GoldenMatch.log(GoldenMatch.lionsInnings().subList(0, 1)), PLAYER, TEAM);
        assertThat(started.innings()).hasSize(1);
        assertThat(started.innings().get(0).worm()).hasSize(1);
        assertThat(started.commentary()).isEmpty();
    }

    @Test
    void namesAndNumbersOnlyNoIdsAreInTheResult() {
        var text = golden().toString();
        assertThat(text).doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
