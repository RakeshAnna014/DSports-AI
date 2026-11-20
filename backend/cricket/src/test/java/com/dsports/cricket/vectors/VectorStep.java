package com.dsports.cricket.vectors;

import com.dsports.cricket.domain.scoring.ScoreCommand;
import com.dsports.cricket.domain.scoring.ScoringLog;

/** One step of a vector: a command the scorer sends, or an undo. */
sealed interface VectorStep {

    record Command(ScoreCommand command) implements VectorStep {
    }

    record Undo(ScoringLog.UndoMode mode) implements VectorStep {
    }

    static VectorStep of(ScoreCommand command) {
        return new Command(command);
    }

    static VectorStep undo(ScoringLog.UndoMode mode) {
        return new Undo(mode);
    }
}
