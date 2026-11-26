package com.dsports.cricket.vectors;

import com.dsports.cricket.domain.rules.InningsState;
import com.dsports.cricket.domain.scoring.LogEntry;
import com.dsports.cricket.domain.scoring.MatchScore;
import com.dsports.cricket.domain.scoring.MatchScoring;
import com.dsports.cricket.domain.scoring.MatchSetup;
import com.dsports.cricket.domain.scoring.ScoreCommand;
import com.dsports.cricket.domain.scoring.ScoreOutcome;
import com.dsports.cricket.domain.scoring.ScoringLog;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Plays steps through the real engine the way the scoring use case does (a log of numbered entries, an undo that voids what {@link ScoringLog#planUndo} says,
 * a state that is the fold of what is still in force) and says what happened, in the shape the vector files record it. Used both to write the files and to
 * check them, so the two cannot drift apart.
 */
final class VectorRunner {

    static final String NOTHING_TO_UNDO = "NOTHING_TO_UNDO";
    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** What one step did. {@code violation} is a rule code for a refused command and {@link #NOTHING_TO_UNDO} for an undo with nothing to take off. */
    record Result(boolean accepted, String violation, List<Integer> voids, int seq, List<InningsState.Waiver> waivers) {
    }

    private final MatchSetup setup;
    private final List<LogEntry> entries = new ArrayList<>();
    private MatchScore score = MatchScore.empty();

    VectorRunner(MatchSetup setup) {
        this.setup = setup;
    }

    MatchScore score() {
        return score;
    }

    /** The log so far (commands and undos), as the use case would hold it. */
    List<LogEntry> entries() {
        return List.copyOf(entries);
    }

    private int lastSeq() {
        return entries.isEmpty() ? 0 : entries.get(entries.size() - 1).seq();
    }

    Result command(ScoreCommand command) {
        return switch (MatchScoring.apply(setup, score, command)) {
            case ScoreOutcome.Rejected r -> new Result(false, r.violation().name(), List.of(), lastSeq(), List.of());
            case ScoreOutcome.Accepted a -> {
                int seq = lastSeq() + 1;
                entries.add(LogEntry.command(seq, command, new UUID(7, seq), new UUID(8, 1), T0.plusSeconds(seq), a.waivers()));
                score = a.score();
                yield new Result(true, null, List.of(), seq, a.waivers());
            }
        };
    }

    Result undo(ScoringLog.UndoMode mode) {
        var voids = ScoringLog.planUndo(entries, mode);
        if (voids.isEmpty()) {
            return new Result(false, NOTHING_TO_UNDO, List.of(), lastSeq(), List.of());
        }
        int seq = lastSeq() + 1;
        entries.add(LogEntry.undo(seq, voids, new UUID(7, seq), new UUID(8, 1), T0.plusSeconds(seq)));
        score = MatchScoring.fold(setup, ScoringLog.commands(entries));
        return new Result(true, null, voids, seq, List.of());
    }

    /** The full state as the files record it: the stored score, the result (null until the match is complete) and the status. */
    ObjectNode state() {
        var o = NODES.objectNode();
        o.set("score", VectorFormat.tree(VectorFormat.CODEC.scoreJson(score)));
        o.set("result", score.result().map(r -> VectorFormat.tree(VectorFormat.CODEC.resultJson(r))).orElse(NODES.nullNode()));
        o.put("status", score.status().name());
        return o;
    }

    /** What the scorer must do next: start an innings, name a batter, name a bowler, score a ball, or nothing (the match is over). */
    String next() {
        if (score.endedBy() != null || score.isFinished()) {
            return "NONE";
        }
        var current = score.current();
        if (current == null || current.isComplete()) {
            return "START_INNINGS";
        }
        if (current.needsBatter()) {
            return "SELECT_BATTER";
        }
        if (current.needsBowler()) {
            return "SELECT_BOWLER";
        }
        return "DELIVER";
    }

    /** The expectation recorded for a step; {@code withState} adds the whole state (the golden files do, the big random files only now and then). */
    ObjectNode expect(Result result, boolean withState) {
        var o = NODES.objectNode();
        if (!result.accepted()) {
            if (NOTHING_TO_UNDO.equals(result.violation())) {
                o.put("result", "nothing_to_undo");
            } else {
                o.put("result", "rejected");
                o.put("violation", result.violation());
            }
            return o;
        }
        o.put("result", "accepted");
        o.put("seq", result.seq());
        if (!result.voids().isEmpty()) {
            var voids = o.putArray("voids");
            result.voids().forEach(voids::add);
        }
        if (!result.waivers().isEmpty()) {
            var waivers = o.putArray("waivers");
            for (var w : result.waivers()) {
                var item = waivers.addObject();
                item.put("over", w.over());
                item.put("bowlerId", w.bowlerId().toString());
                item.put("kind", w.kind().name());
            }
        }
        var state = state();
        o.put("fp", VectorFormat.fingerprint(state));
        o.put("status", score.status().name());
        o.put("next", next());
        var current = score.current();
        if (current == null || current.endReason() == null) {
            o.putNull("end");
        } else {
            o.put("end", current.endReason().name());
        }
        if (state.get("result").isNull()) {
            o.putNull("resultType");
        } else {
            o.put("resultType", state.get("result").get("type").asText());
        }
        if (withState) {
            o.set("state", state);
        }
        return o;
    }
}
