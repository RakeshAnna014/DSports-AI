package com.dsports.cricket.application.result;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the scorer screen needs, so the client holds no cricket rules: the innings, what is needed next, which dismissals the next ball allows,
 * who may bat or bowl, what an undo would do, and how long the match can still be corrected. Names only; no phones or emails.
 * {@code engineVersion} and {@code stateFingerprint} let a phone check that its own copy of the rules agrees with the server's before it scores offline.
 */
public record ScoringView(UUID matchId, int seq, String matchStatus, Rules rules, List<Innings> innings, String needs, boolean freeHit,
                          Map<String, List<String>> allowedDismissals, List<BatterChoice> eligibleBatters, List<BowlerChoice> bowlers, boolean waiverRequired,
                          Result result, CanUndo canUndo, Instant editableUntil, boolean locked, List<Event> recentEvents,
                          int engineVersion, String stateFingerprint) {

    public record Ref(UUID id, String name) {
    }

    public record Rules(int overs, int ballsPerOver, int playersPerSide, int maxOversPerBowler) {
    }

    public record Extras(int wides, int noBalls, int byes, int legByes, int total) {
    }

    public record Innings(int number, Ref battingTeam, Ref bowlingTeam, String status, String endReason, int runs, int wickets, String overs, int ballsRemaining,
                          Integer target, Integer runsNeeded, Extras extras, Ref striker, Ref nonStriker, Ref bowler, List<String> thisOver, List<Batter> batters,
                          List<Ref> yetToBat, List<Bowler> bowlers, List<Fall> fallOfWickets, List<WaiverNote> waivers) {
    }

    public record Batter(Ref player, int runs, int balls, int fours, int sixes, String howOut, boolean notOut, boolean atCrease) {
    }

    public record Bowler(Ref player, String overs, int creditedOvers, int maidens, int runs, int wickets, int wides, int noBalls, BigDecimal economy) {
    }

    public record Fall(int number, int runs, Ref batter, String overs, String howOut) {
    }

    /** {@code over} is the over's number as people say it (the first over is 1). */
    public record WaiverNote(int over, Ref bowler, String kind, String text) {
    }

    public record BatterChoice(Ref player, boolean returning, String note) {
    }

    public record BowlerChoice(Ref player, String overs, int creditedOvers, int limit, boolean canBowlNext, boolean canReplaceMidOver, String why) {
    }

    public record Result(String type, Ref winner, int margin, int ballsRemaining, String text) {
    }

    public record CanUndo(boolean allowed, String describe, boolean crossesInnings, String blockedReason) {
    }

    /**
     * {@code mine} is whether the person asking made this event; {@code clientEventId} is shown for their own events only (it is how a phone knows the server
     * already has a ball it sent). {@code tappedAt} is when the scorer says they tapped, kept for the timeline only: no rule, lock or ordering ever uses it.
     */
    public record Event(int seq, String kind, String text, Ref by, Instant at, boolean mine, UUID clientEventId, Instant tappedAt) {
    }

    /** What a scoring call returns: the view after it, and whether the call was a retry of one already in the log. */
    public record Outcome(ScoringView view, boolean replayed, int eventSeq) {
    }

    /** The body of a 409 SCORE_CONFLICT: where the match is now and what happened since the scorer last looked. */
    public record Conflict(int currentSeq, ScoringView view, List<Event> eventsSince) {
    }

    /** The body of a 422 SCORING_REJECTED. */
    public record Rejection(String violation) {
    }
}
