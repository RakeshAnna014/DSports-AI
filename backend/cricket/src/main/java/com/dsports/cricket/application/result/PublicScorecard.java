package com.dsports.cricket.application.result;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A started match as anyone may read it, signed in or not: names and numbers only. The only ids are the match's own (which is in the link) and each batter's and bowler's player id
 * (so a name can link to that player's public career statistics; a player id opens nothing but those statistics), and there are no emails or phones, no booking, claim, scorer or user data, and nothing about who is allowed to score or correct it. It is built from the stored snapshot, so it can show only
 * what the scorers have recorded.
 */
public record PublicScorecard(UUID matchId, int seq, String status, boolean live, String teamA, String teamB, int overs, String city, String venue, Instant scheduledAt,
                              List<Innings> innings, Result result) {

    public record Extras(int wides, int noBalls, int byes, int legByes, int total) {
    }

    /** {@code runRate} is runs per over so far and {@code requiredRate} what the chase still needs per over; either is null when it cannot be worked out. */
    public record Innings(int number, String battingTeam, String bowlingTeam, String status, String endReason, int runs, int wickets, String overs, int ballsRemaining,
                          Integer target, Integer runsNeeded, BigDecimal runRate, BigDecimal requiredRate, Extras extras, List<Batter> batters, List<String> yetToBat,
                          List<Bowler> bowlers, List<Fall> fallOfWickets, List<Waiver> waivers) {
    }

    public record Batter(UUID playerId, String name, int runs, int balls, int fours, int sixes, String howOut, boolean notOut, boolean atCrease, boolean striker) {
    }

    public record Bowler(UUID playerId, String name, String overs, int maidens, int runs, int wickets, int wides, int noBalls, BigDecimal economy, boolean bowling) {
    }

    public record Fall(int number, int runs, String batter, String overs, String howOut) {
    }

    /** {@code over} is the over's number as people say it (the first over is 1). */
    public record Waiver(int over, String text) {
    }

    public record Result(String type, String winner, String text) {
    }
}
