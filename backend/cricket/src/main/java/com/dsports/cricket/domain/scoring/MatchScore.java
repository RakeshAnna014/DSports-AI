package com.dsports.cricket.domain.scoring;

import com.dsports.cricket.domain.model.MatchStatus;
import com.dsports.cricket.domain.rules.InningsState;
import com.dsports.cricket.domain.rules.MatchResultCalculator;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The scored match: its innings (none, one or two) and whether it was ended by hand. Everything else (the status, the result) is worked out from these,
 * so nothing can disagree with them.
 */
public record MatchScore(List<InningsState> innings, ScoreCommand.EndMatch.Reason endedBy) {

    public MatchScore {
        innings = List.copyOf(innings);
    }

    public static MatchScore empty() {
        return new MatchScore(List.of(), null);
    }

    public InningsState current() {
        return innings.isEmpty() ? null : innings.get(innings.size() - 1);
    }

    MatchScore withInnings(List<InningsState> newInnings) {
        return new MatchScore(newInnings, endedBy);
    }

    MatchScore withLastInnings(InningsState state) {
        var all = new ArrayList<>(innings);
        all.set(all.size() - 1, state);
        return withInnings(all);
    }

    public MatchScore ended(ScoreCommand.EndMatch.Reason reason) {
        return new MatchScore(innings, reason);
    }

    /** The match status this score implies. */
    public MatchStatus status() {
        if (endedBy != null) {
            return endedBy == ScoreCommand.EndMatch.Reason.ABANDONED ? MatchStatus.ABANDONED : MatchStatus.NO_RESULT;
        }
        return switch (innings.size()) {
            case 0 -> MatchStatus.TOSS_DONE;
            case 1 -> innings.get(0).isComplete() ? MatchStatus.INNINGS_BREAK : MatchStatus.IN_PROGRESS;
            default -> innings.get(1).isComplete() ? MatchStatus.COMPLETED : MatchStatus.IN_PROGRESS;
        };
    }

    /** True once nothing more will be scored: completed, abandoned or no result. */
    public boolean isFinished() {
        var status = status();
        return status == MatchStatus.COMPLETED || status == MatchStatus.ABANDONED || status == MatchStatus.NO_RESULT;
    }

    /** The result of a completed match, recomputed from the two innings every time. */
    public Optional<MatchResultCalculator.Result> result() {
        return status() == MatchStatus.COMPLETED ? Optional.of(MatchResultCalculator.compute(innings.get(0), innings.get(1))) : Optional.empty();
    }
}
