package com.dsports.cricket.domain.scoring;

import com.dsports.cricket.domain.model.BallType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.dsports.cricket.domain.scoring.GoldenMatch.SETUP;
import static com.dsports.cricket.domain.scoring.GoldenMatch.lion;
import static com.dsports.cricket.domain.scoring.GoldenMatch.tiger;
import static org.assertj.core.api.Assertions.assertThat;

/** Career numbers worked out from the hand-scored golden match (Lions 22/2, Tigers chase 23 and win off the last ball). */
class CareerStatsTest {

    private static MatchScore score(List<ScoreCommand> commands) {
        return MatchScoring.fold(SETUP, commands);
    }

    private static List<CareerStats.Match> golden() {
        return CareerStats.ofMatch(SETUP, score(GoldenMatch.wholeMatch()), BallType.LEATHER);
    }

    private static CareerStats.Match of(List<CareerStats.Match> rows, java.util.UUID id) {
        return rows.stream().filter(r -> r.playerId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void everyPlayerOfBothElevensHasARowEvenIfHeNeitherBattedNorBowled() {
        var rows = golden();
        assertThat(rows).hasSize(22);
        var idle = of(rows, lion(11));
        assertThat(idle).extracting("inningsBatted", "inningsBowled", "runs", "wickets", "catches").containsOnly(0);
        assertThat(idle.highestNotOut()).isFalse();
    }

    @Test
    void aMatchThatIsNotCompletedCountsForNobody() {
        assertThat(CareerStats.ofMatch(SETUP, score(GoldenMatch.lionsInnings()), BallType.LEATHER)).isEmpty();
        assertThat(CareerStats.ofMatch(SETUP, MatchScore.empty(), BallType.LEATHER)).isEmpty();
        var abandoned = score(GoldenMatch.lionsInnings()).ended(ScoreCommand.EndMatch.Reason.ABANDONED);
        assertThat(CareerStats.ofMatch(SETUP, abandoned, BallType.LEATHER)).isEmpty();
    }

    @Test
    void batterRunsAndBallsAddUpToTheInningsTotalLessExtras() {
        var score = score(GoldenMatch.wholeMatch());
        var rows = golden();
        for (int n = 0; n < 2; n++) {
            var innings = score.innings().get(n);
            var ids = (n == 0 ? SETUP.xiA() : SETUP.xiB());
            int batterRuns = ids.stream().mapToInt(id -> of(rows, id).runs()).sum();
            assertThat(batterRuns + innings.extras().total()).as("innings %d", n + 1).isEqualTo(innings.runs());
        }
        assertThat(rows.stream().mapToInt(CareerStats.Match::wickets).sum()).as("wickets credited to bowlers").isEqualTo(1);
    }

    @Test
    void bowlersGetOnlyTheirOwnWicketsAndRunsAndTheFielderGetsTheRunOut() {
        var rows = golden();
        var bowled = of(rows, tiger(1));
        assertThat(bowled.wickets()).as("bowled lion 3").isEqualTo(1);
        assertThat(bowled.bestWickets()).isEqualTo(1);
        assertThat(bowled.inningsBowled()).isEqualTo(1);
        assertThat(of(rows, tiger(3)).runOuts()).as("the run out of lion 1 is the fielder's").isEqualTo(1);
        assertThat(of(rows, tiger(3)).wickets()).as("a run out is no bowler's wicket").isZero();
    }

    @Test
    void aBatterWhoCameInButWasNotOutAndOneWhoWasBowledForNothing() {
        var rows = golden();
        var duck = of(rows, lion(3));
        assertThat(duck).extracting("inningsBatted", "runs", "notOuts", "ducks").containsExactly(1, 0, 0, 1);
        var hurt = of(rows, lion(4));
        assertThat(hurt.notOuts()).as("retired hurt is not out").isEqualTo(1);
        assertThat(hurt.ducks()).as("not out for nought is no duck").isZero();
        var chase = of(rows, tiger(1));
        assertThat(chase.notOuts()).isEqualTo(1);
        assertThat(chase.runs() + of(rows, tiger(2)).runs() + score(GoldenMatch.wholeMatch()).innings().get(1).extras().total()).isEqualTo(23);
    }

    @Test
    void sixesAndFoursAreCountedFromTheBatter() {
        var rows = golden();
        assertThat(rows.stream().mapToInt(CareerStats.Match::sixes).sum()).isEqualTo(1 + 2);
        assertThat(of(rows, tiger(1)).sixes() + of(rows, tiger(2)).sixes()).isEqualTo(2);
    }

    @Test
    void theCareerAddsMatchesAndKeepsTheBallTypesApart() {
        var leather = golden();
        var tennis = CareerStats.ofMatch(SETUP, score(GoldenMatch.wholeMatch()), BallType.TENNIS);
        var all = new ArrayList<>(leather);
        all.addAll(leather);
        all.addAll(tennis);
        var id = tiger(1);
        var one = CareerStats.total(id, BallType.LEATHER, leather).orElseThrow();
        var two = CareerStats.total(id, BallType.LEATHER, all).orElseThrow();
        assertThat(two.matches()).isEqualTo(3 - 1);
        assertThat(two.runs()).isEqualTo(one.runs() * 2);
        assertThat(two.wickets()).isEqualTo(one.wickets() * 2);
        assertThat(two.highest()).isEqualTo(one.highest());
        assertThat(CareerStats.total(id, BallType.TENNIS, all).orElseThrow().matches()).isEqualTo(1);
        assertThat(CareerStats.total(id, BallType.LEATHER, tennis)).isEmpty();
    }

    @Test
    void aPlayerWithNoRowHasNoCareer() {
        assertThat(CareerStats.total(lion(1), BallType.LEATHER, List.of())).isEmpty();
        assertThat(CareerStats.total(new java.util.UUID(9, 9), BallType.LEATHER, golden())).isEmpty();
    }

    @Test
    void highestScoreAndBestBowlingAreTheBestOfTheMatchesAndNotSums() {
        var id = new java.util.UUID(5, 5);
        var a = new CareerStats.Match(id, BallType.LEATHER, 1, 45, 30, 1, 4, 1, 45, true, 0, 0, 0, 1, 12, 20, 2, 0, 2, 20, 0, 0, 0);
        var b = new CareerStats.Match(id, BallType.LEATHER, 1, 45, 25, 0, 4, 1, 45, false, 0, 0, 0, 1, 12, 14, 2, 1, 2, 14, 0, 0, 0);
        var c = new CareerStats.Match(id, BallType.LEATHER, 1, 60, 40, 0, 5, 2, 60, false, 1, 0, 0, 1, 12, 30, 5, 0, 5, 30, 1, 0, 0);
        var career = CareerStats.total(id, BallType.LEATHER, List.of(a, b, c)).orElseThrow();
        assertThat(career).extracting("matches", "runs", "highest", "fifties", "fiveWickets", "bestWickets", "bestRuns", "catches").containsExactly(3, 150, 60, 1, 1, 5, 30, 1);
        var tie = CareerStats.total(id, BallType.LEATHER, List.of(b, a)).orElseThrow();
        assertThat(tie.highest()).isEqualTo(45);
        assertThat(tie.highestNotOut()).as("the not-out 45 is the better 45").isTrue();
        var fewerRuns = CareerStats.total(id, BallType.LEATHER, List.of(a, b)).orElseThrow();
        assertThat(fewerRuns).extracting("bestWickets", "bestRuns").containsExactly(2, 14);
    }

    @Test
    void theRowsOfAFinishedMatchAreTheSameWhenTheyAreWorkedOutAgain() {
        assertThat(golden()).isEqualTo(golden());
    }
}
