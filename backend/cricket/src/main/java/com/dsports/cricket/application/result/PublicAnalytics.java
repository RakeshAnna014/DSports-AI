package com.dsports.cricket.application.result;

import com.dsports.cricket.domain.scoring.MatchAnalytics;

import java.util.List;

/**
 * The charts and the commentary of a started match, for anyone: runs in each over, the running total after each over (the worm and the run rate), the partnership
 * for each wicket, and a line of commentary for each ball, newest last. Names and numbers only; no id but the match's own. {@code seq} is the seq of the last event,
 * so a viewer can tell whether anything new was scored.
 */
public record PublicAnalytics(int seq, List<MatchAnalytics.InningsAnalytics> innings, List<MatchAnalytics.CommentaryLine> commentary, List<MatchAnalytics.ShotPoint> shots) {
    public PublicAnalytics {
        innings = List.copyOf(innings);
        commentary = List.copyOf(commentary);
        shots = List.copyOf(shots);
    }
}
