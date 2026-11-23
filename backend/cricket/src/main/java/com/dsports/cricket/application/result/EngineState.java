package com.dsports.cricket.application.result;

import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.rules.InningsState;
import com.dsports.cricket.domain.scoring.LogEntry;
import com.dsports.cricket.domain.scoring.MatchScoring;
import com.dsports.cricket.domain.scoring.MatchSetup;
import com.dsports.cricket.domain.scoring.ScoringLog;
import com.dsports.cricket.domain.scoring.StateFingerprint;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a phone needs in order to score offline with its own copy of the rules: the match's setup and the whole log (commands, undos and unlocks, each with its
 * seq), the version of the scoring rules and the fingerprint of the state that log gives. The phone folds the log with its own engine and compares the
 * fingerprint with this one before it predicts anything (a different version or a different fingerprint switches offline scoring off, and nothing queued is lost).
 * Names, phones and emails are not here: ids only. Another scorer's events carry no client event id.
 */
public record EngineState(UUID matchId, int engineVersion, int seq, String stateFingerprint, boolean ready, Setup setup, List<Entry> entries) {

    public record Setup(int overs, MatchRules rules, UUID teamA, UUID teamB, UUID battingFirst, List<UUID> xiA, List<UUID> xiB) {
    }

    /**
     * One log entry. {@code command} is the wire body of a COMMAND entry, {@code voids} what an UNDO entry took off, {@code waivers} the rules the engine set aside
     * when it accepted a command. {@code clientEventId} is present only for the asking scorer's own events.
     */
    public record Entry(int seq, String kind, Map<String, Object> command, List<Integer> voids, UUID clientEventId, boolean mine, List<InningsState.Waiver> waivers) {
    }

    public static EngineState of(UUID matchId, MatchSetup setup, List<LogEntry> entries, UUID viewer) {
        var score = MatchScoring.fold(setup, ScoringLog.commands(entries));
        boolean ready = setup.battingFirstTeamId() != null && setup.xiA().size() >= 2 && setup.xiB().size() >= 2;
        var out = entries.stream().map(e -> {
            boolean mine = e.actorId().equals(viewer);
            return new Entry(e.seq(), e.kind().name(), e.command() == null ? null : CommandWire.body(e.command()), e.voids(), mine ? e.clientEventId() : null, mine, e.waivers());
        }).toList();
        return new EngineState(matchId, MatchScoring.ENGINE_VERSION, entries.isEmpty() ? 0 : entries.get(entries.size() - 1).seq(), StateFingerprint.of(score), ready,
            new Setup(setup.overs(), setup.rules(), setup.teamAId(), setup.teamBId(), setup.battingFirstTeamId(), setup.xiA(), setup.xiB()), out);
    }
}
