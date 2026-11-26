package com.dsports.cricket.application.result;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One line of the public live-matches list: who plays, where, and the score of each innings so far. Names and numbers only. */
public record PublicMatchSummary(UUID matchId, String status, String teamA, String teamB, int overs, String city, String venue, Instant scheduledAt, List<Score> scores) {

    /** {@code current} marks the innings being played (or the last one played). */
    public record Score(String team, int runs, int wickets, String overs, boolean current) {
    }

    /** A page of a public list. {@code hasNext} says whether another page follows, so no count query is needed. */
    public record Page<T>(List<T> items, int page, int size, boolean hasNext) {
    }
}
