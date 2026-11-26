package com.dsports.cricket.domain.scoring;

import java.time.Duration;
import java.time.Instant;

/**
 * How long a finished match can still be corrected. The 24 hours run from the <strong>first</strong> time the match finished (completed, abandoned or no
 * result) and never restart: undoing and finishing again does not extend them. Only an administrator's unlock grants a fresh 24 hours, from the moment
 * of the unlock. Once the time is up every command and undo is refused, whatever the state of the match.
 */
public record EditWindow(Instant firstFinishedAt, Instant unlockedUntil) {

    public static final Duration LENGTH = Duration.ofHours(24);

    public static EditWindow open() {
        return new EditWindow(null, null);
    }

    /** The window after a command: if the match has now finished for the first time, the clock starts. It is never cleared. */
    public EditWindow after(MatchScore score, Instant now) {
        return firstFinishedAt == null && score.isFinished() ? new EditWindow(now, unlockedUntil) : this;
    }

    /** The moment correcting stops, or null if the match has never finished. */
    public Instant editableUntil() {
        if (firstFinishedAt == null) {
            return null;
        }
        var byCompletion = firstFinishedAt.plus(LENGTH);
        return unlockedUntil != null && unlockedUntil.isAfter(byCompletion) ? unlockedUntil : byCompletion;
    }

    public boolean isLocked(Instant now) {
        var until = editableUntil();
        return until != null && now.isAfter(until);
    }

    /** Grants 24 hours from {@code now}. Only meaningful when locked; the use case checks that. */
    public EditWindow unlocked(Instant now) {
        return new EditWindow(firstFinishedAt, now.plus(LENGTH));
    }
}
