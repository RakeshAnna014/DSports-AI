package com.dsports.cricket.application;

import com.dsports.cricket.application.result.PublicPlayerStats;
import com.dsports.cricket.domain.model.BallType;
import com.dsports.cricket.domain.scoring.CareerStats;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The figures a career page prints are exact, and a ratio that cannot be worked out is null (never zero, never NaN). */
class PublicPlayerStatsTest {

    private static final UUID ID = new UUID(5, 5);

    private static CareerStats.Career career(List<CareerStats.Match> rows) {
        return CareerStats.total(ID, BallType.LEATHER, rows).orElseThrow();
    }

    private static CareerStats.Match row(int inningsBatted, int runs, int balls, int notOuts, int inningsBowled, int ballsBowled, int conceded, int wickets) {
        return new CareerStats.Match(ID, BallType.LEATHER, inningsBatted, runs, balls, notOuts, 0, 0, runs, notOuts > 0, 0, 0, 0, inningsBowled, ballsBowled, conceded, wickets, 0, wickets, conceded, 0, 0, 0);
    }

    @Test
    void averagesAndRatesAreWorkedOutFromTheTotalsToTwoDecimals() {
        var line = PublicPlayerStats.line(career(List.of(row(1, 50, 40, 0, 1, 15, 20, 2), row(1, 30, 25, 1, 0, 0, 0, 0))), 6);
        assertThat(line.batting().average()).as("80 runs, one dismissal").isEqualByComparingTo(new BigDecimal("80.00"));
        assertThat(line.batting().strikeRate()).isEqualByComparingTo(new BigDecimal("123.08"));
        assertThat(line.bowling().overs()).isEqualTo("2.3");
        assertThat(line.bowling().economy()).as("20 runs off 15 balls").isEqualByComparingTo(new BigDecimal("8.00"));
        assertThat(line.bowling().average()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(line.bowling().strikeRate()).isEqualByComparingTo(new BigDecimal("7.50"));
        assertThat(line.bowling().best()).isEqualTo("2/20");
    }

    @Test
    void aPlayerWhoNeverBowledOrWasNeverOutHasNullRatiosAndNoBestFigures() {
        var line = PublicPlayerStats.line(career(List.of(row(1, 12, 10, 1, 0, 0, 0, 0))), 6);
        assertThat(line.batting().average()).isNull();
        assertThat(line.bowling()).extracting("average", "economy", "strikeRate", "best").containsOnlyNulls();
        assertThat(line.bowling().overs()).isEqualTo("0.0");
        assertThat(line.batting().highestNotOut()).isTrue();
    }

    @Test
    void aPlayerWhoNeverBattedHasNullBattingRatios() {
        var line = PublicPlayerStats.line(career(List.of(row(0, 0, 0, 0, 1, 6, 6, 0))), 6);
        assertThat(line.batting().average()).isNull();
        assertThat(line.batting().strikeRate()).isNull();
        assertThat(line.bowling().wickets()).isZero();
        assertThat(line.bowling().average()).as("no wicket, no bowling average").isNull();
        assertThat(line.bowling().best()).isEqualTo("0/6");
    }
}
