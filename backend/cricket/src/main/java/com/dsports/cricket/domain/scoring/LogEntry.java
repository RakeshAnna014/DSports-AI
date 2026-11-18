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
                       List<InningsState.Waiver> waivers) {

    public enum Kind { COMMAND, UNDO, ADMIN_UNLOCK }

    public LogEntry {
        voids = List.copyOf(voids);
        waivers = List.copyOf(waivers);
    }

    public static LogEntry command(int seq, ScoreCommand command, UUID clientEventId, UUID actorId, Instant at, List<InningsState.Waiver> waivers) {
        return new LogEntry(seq, Kind.COMMAND, command, List.of(), null, clientEventId, actorId, at, waivers);
    }

    public static LogEntry undo(int seq, List<Integer> voids, UUID clientEventId, UUID actorId, Instant at) {
        return new LogEntry(seq, Kind.UNDO, null, voids, null, clientEventId, actorId, at, List.of());
    }

    public static LogEntry unlock(int seq, String reason, UUID clientEventId, UUID actorId, Instant at) {
        return new LogEntry(seq, Kind.ADMIN_UNLOCK, null, List.of(), reason, clientEventId, actorId, at, List.of());
    }
}
