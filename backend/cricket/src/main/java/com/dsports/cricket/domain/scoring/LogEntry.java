package com.dsports.cricket.domain.scoring;

import com.dsports.cricket.domain.rules.InningsState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of the append-only event log. A COMMAND entry holds a scoring command. An UNDO entry voids earlier commands ({@code voids}, the seqs it
 * takes off) and deletes nothing. An ADMIN_UNLOCK entry records who reopened a locked match and why. {@code waivers} are the rules the engine set aside
 * when it accepted the command.
 */
public record LogEntry(int seq, Kind kind, ScoreCommand command, List<Integer> voids, String reason, UUID clientEventId, UUID actorId, Instant at,
                       List<InningsState.Waiver> waivers, Instant tappedAt) {

    /** How long before the server received an entry its tapped time may be: a match scored offline for longer than this is not a case this feature serves. */
    public static final java.time.Duration TAPPED_LOOKBACK = java.time.Duration.ofDays(30);

    public enum Kind { COMMAND, UNDO, ADMIN_UNLOCK }

    public LogEntry {
        voids = List.copyOf(voids);
        waivers = List.copyOf(waivers);
    }

    public static LogEntry command(int seq, ScoreCommand command, UUID clientEventId, UUID actorId, Instant at, List<InningsState.Waiver> waivers) {
        return new LogEntry(seq, Kind.COMMAND, command, List.of(), null, clientEventId, actorId, at, waivers, null);
    }

    public static LogEntry undo(int seq, List<Integer> voids, UUID clientEventId, UUID actorId, Instant at) {
        return new LogEntry(seq, Kind.UNDO, null, voids, null, clientEventId, actorId, at, List.of(), null);
    }

    /**
     * The same entry with the time the scorer says they tapped. It is for the timeline of an offline-scored ball and nothing else: it is never part of the
     * request fingerprint, never read by a rule, the 24-hour window or the ordering (the seq and the server's own {@code at} decide those), and it is never later
     * than the moment the server received the entry, so a wrong phone clock cannot place a ball in the future. A time more than {@link #TAPPED_LOOKBACK} before
     * that is not believable for a ball scored offline (and could not always be stored), so it is not kept.
     */
    public LogEntry withTappedAt(Instant tapped) {
        var shown = tapped == null || tapped.isBefore(at.minus(TAPPED_LOOKBACK)) ? null : (tapped.isAfter(at) ? at : tapped);
        return new LogEntry(seq, kind, command, voids, reason, clientEventId, actorId, at, waivers, shown);
    }

    public static LogEntry unlock(int seq, String reason, UUID clientEventId, UUID actorId, Instant at) {
        return new LogEntry(seq, Kind.ADMIN_UNLOCK, null, List.of(), reason, clientEventId, actorId, at, List.of(), null);
    }
}
