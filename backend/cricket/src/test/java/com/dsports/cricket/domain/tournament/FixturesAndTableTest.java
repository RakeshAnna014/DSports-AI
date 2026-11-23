package com.dsports.cricket.domain.tournament;

import com.dsports.cricket.domain.tournament.Fixtures.Pairing;
import com.dsports.cricket.domain.tournament.PointsTable.Outcome;
import com.dsports.cricket.domain.tournament.PointsTable.Played;
import com.dsports.cricket.domain.tournament.PointsTable.Rules;
import com.dsports.cricket.domain.tournament.PointsTable.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixturesAndTableTest {

    private static UUID t(int n) {
        return new UUID(0, n);
    }

    private static List<UUID> teams(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(FixturesAndTableTest::t).toList();
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    @Test
    void aRoundRobinHasEveryPairExactlyOnceForAnyNumberOfTeamsAndNoTeamPlaysTwiceInARound() {
        for (int n : new int[] {2, 3, 4, 5, 8, 11, 16}) {
            var fixtures = Fixtures.roundRobin(teams(n));
            assertThat(fixtures).as("%d teams", n).hasSize(n * (n - 1) / 2);
            var pairs = fixtures.stream().map(f -> new HashSet<>(List.of(f.a(), f.b()))).collect(Collectors.toSet());
            assertThat(pairs).hasSize(fixtures.size());
            fixtures.stream().collect(Collectors.groupingBy(Pairing::round)).forEach((round, in) -> {
                var seen = in.stream().flatMap(f -> java.util.stream.Stream.of(f.a(), f.b())).toList();
                assertThat(seen).as("%d teams, round %d", n, round).doesNotHaveDuplicates();
            });
            assertThat(fixtures.stream().mapToInt(Pairing::round).max().orElse(0)).isEqualTo(n % 2 == 0 ? n - 1 : n);
        }
    }

    @Test
    void theSameTeamsInTheSameOrderAlwaysGiveTheSameFixtures() {
        assertThat(Fixtures.roundRobin(teams(7))).isEqualTo(Fixtures.roundRobin(teams(7)));
        assertThat(Fixtures.roundRobin(List.of())).isEmpty();
        assertThat(Fixtures.roundRobin(teams(1))).isEmpty();
    }

    @Test
    void aTeamCannotBeInATournamentTwice() {
        assertThatThrownBy(() -> Fixtures.roundRobin(List.of(t(1), t(2), t(1)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Fixtures.knockoutFirstRound(List.of(t(1), t(1)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aKnockoutPairsTheBestSeedWithTheWorstAndGivesTheByesToTheBestSeeds() {
        var four = Fixtures.knockoutFirstRound(teams(4));
        assertThat(four).extracting(Pairing::a, Pairing::b).containsExactly(org.assertj.core.groups.Tuple.tuple(t(1), t(4)), org.assertj.core.groups.Tuple.tuple(t(2), t(3)));
        var five = Fixtures.knockoutFirstRound(teams(5));
        assertThat(five).hasSize(4);
        assertThat(five.stream().filter(Pairing::isBye).map(Pairing::a)).as("the three best seeds have byes").containsExactly(t(1), t(2), t(3));
        assertThat(five.get(3)).extracting(Pairing::a, Pairing::b).containsExactly(t(4), t(5));
        assertThat(Fixtures.knockoutFirstRound(teams(2))).hasSize(1);
        assertThat(Fixtures.knockoutFirstRound(teams(1))).isEmpty();
        assertThat(Fixtures.knockoutFirstRound(teams(16))).hasSize(8).noneMatch(Pairing::isBye);
    }

    // ---- the points table -----------------------------------------------------------------------------------------

    private static Played won(UUID a, UUID b, UUID winner, int runsA, int ballsA, boolean outA, int runsB, int ballsB, boolean outB) {
        return new Played(a, b, Outcome.RESULT, winner, new Side(a, runsA, ballsA, outA, 120), new Side(b, runsB, ballsB, outB, 120));
    }

    @Test
    void aWinIsTwoPointsATieOneAndANoResultOneAndTheTableIsInPointsOrder() {
        var results = List.of(
            won(t(1), t(2), t(1), 150, 120, false, 120, 120, false),
            new Played(t(1), t(3), Outcome.TIE, null, new Side(t(1), 100, 120, false, 120), new Side(t(3), 100, 120, false, 120)),
            new Played(t(2), t(3), Outcome.NO_RESULT, null, null, null));
        var table = PointsTable.compute(teams(3), results, Rules.standard(), 6);
        assertThat(table).extracting(PointsTable.Row::team).containsExactly(t(1), t(3), t(2));
        assertThat(table.get(0)).extracting("played", "won", "lost", "tied", "noResult", "points").containsExactly(2, 1, 0, 1, 0, 3);
        assertThat(table.get(1)).extracting("played", "won", "lost", "tied", "noResult", "points").containsExactly(2, 0, 0, 1, 1, 2);
        assertThat(table.get(2)).extracting("played", "won", "lost", "tied", "noResult", "points").containsExactly(2, 0, 1, 0, 1, 1);
    }

    @Test
    void netRunRateIsRunsPerOverScoredMinusRunsPerOverConceded() {
        var table = PointsTable.compute(teams(2), List.of(won(t(1), t(2), t(1), 150, 120, false, 120, 120, false)), Rules.standard(), 6);
        assertThat(table.get(0).netRunRate()).isEqualByComparingTo(new BigDecimal("1.500"));
        assertThat(table.get(1).netRunRate()).isEqualByComparingTo(new BigDecimal("-1.500"));
    }

    @Test
    void aSideBowledOutIsCountedAsHavingFacedItsWholeQuota() {
        // Team 2 was all out for 60 in 10 overs (60 balls) of a 20 over quota: the rate uses 120 balls, not 60.
        var result = won(t(1), t(2), t(1), 100, 120, false, 60, 60, true);
        var table = PointsTable.compute(teams(2), List.of(result), Rules.standard(), 6);
        assertThat(table.get(0).netRunRate()).as("scored 100 off 20 conceded 60 off 20").isEqualByComparingTo(new BigDecimal("2.000"));
        assertThat(table.get(1).netRunRate()).isEqualByComparingTo(new BigDecimal("-2.000"));
    }

    @Test
    void aShortenedChaseUsesTheOversItWasGiven() {
        var first = new Side(t(1), 120, 120, false, 120);
        var second = new Side(t(2), 80, 60, false, 60);   // 10 over revised chase, lost
        var table = PointsTable.compute(teams(2), List.of(new Played(t(1), t(2), Outcome.RESULT, t(1), first, second)), Rules.standard(), 6);
        assertThat(table.get(0).netRunRate()).as("6.000 scored per over minus 8.000 conceded per over").isEqualByComparingTo(new BigDecimal("-2.000"));
    }

    @Test
    void aTeamThatHasNotPlayedHasZeroRateNotADivisionByZeroAndTiesBreakByWinsThenRateThenHeadToHead() {
        var idle = PointsTable.compute(teams(3), List.of(), Rules.standard(), 6);
        assertThat(idle).extracting(PointsTable.Row::netRunRate).allSatisfy(r -> assertThat(r).isEqualByComparingTo(BigDecimal.ZERO));
        assertThat(idle).extracting(PointsTable.Row::team).as("level on everything: id order").containsExactly(t(1), t(2), t(3));
        // 1 beat 2, 2 beat 3, 3 beat 1, all by the same margin: level on points, wins and rate (a three-way tie stays in id order).
        var cycle = List.of(won(t(1), t(2), t(1), 100, 120, false, 90, 120, false), won(t(2), t(3), t(2), 100, 120, false, 90, 120, false), won(t(3), t(1), t(3), 100, 120, false, 90, 120, false));
        assertThat(PointsTable.compute(teams(3), cycle, Rules.standard(), 6)).extracting(PointsTable.Row::team).containsExactly(t(1), t(2), t(3));
    }

    @Test
    void headToHeadSeparatesTwoTeamsThatAreLevelOnPointsWinsAndRate() {
        // 4 beats 2 and 3; 2 beats 1; 1 beats 3, every margin 100 to 90: teams 1 and 2 are level on points, wins and rate, and 2 beat 1.
        var results = List.of(won(t(4), t(2), t(4), 100, 120, false, 90, 120, false), won(t(4), t(3), t(4), 100, 120, false, 90, 120, false),
            won(t(2), t(1), t(2), 100, 120, false, 90, 120, false), won(t(1), t(3), t(1), 100, 120, false, 90, 120, false));
        var table = PointsTable.compute(teams(4), results, Rules.standard(), 6);
        assertThat(table.get(1).netRunRate()).isEqualByComparingTo(table.get(2).netRunRate());
        assertThat(table).extracting(PointsTable.Row::team).as("without the head to head it would be id order 1 then 2").containsExactly(t(4), t(2), t(1), t(3));
    }

    @Test
    void aResultInvolvingATeamThatIsNotInTheTournamentIsIgnored() {
        var table = PointsTable.compute(teams(2), List.of(won(t(1), t(9), t(1), 100, 120, false, 90, 120, false)), Rules.standard(), 6);
        assertThat(table).extracting(PointsTable.Row::played).containsOnly(0);
    }
}
