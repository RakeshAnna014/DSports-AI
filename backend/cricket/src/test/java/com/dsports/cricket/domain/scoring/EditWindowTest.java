package com.dsports.cricket.domain.scoring;

import com.dsports.cricket.domain.scoring.ScoreCommand.EndMatch;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static com.dsports.cricket.domain.scoring.GoldenMatch.SETUP;
import static org.assertj.core.api.Assertions.assertThat;

class EditWindowTest {

    private static final Instant T0 = Instant.parse("2026-11-20T18:00:00Z");

    private static MatchScore finished() {
        return MatchScoring.fold(SETUP, GoldenMatch.wholeMatch());
    }

    private static MatchScore reopened() {
        var all = GoldenMatch.wholeMatch();
        return MatchScoring.fold(SETUP, all.subList(0, all.size() - 1));
    }

    @Test
    void a_match_that_has_never_finished_is_never_locked() {
        var window = EditWindow.open().after(reopened(), T0);

        assertThat(window.editableUntil()).isNull();
        assertThat(window.isLocked(T0.plus(Duration.ofDays(30)))).isFalse();
    }

    @Test
    void the_clock_starts_when_the_match_finishes_and_runs_for_24_hours() {
        var window = EditWindow.open().after(finished(), T0);

        assertThat(window.firstFinishedAt()).isEqualTo(T0);
        assertThat(window.editableUntil()).isEqualTo(T0.plus(Duration.ofHours(24)));
        assertThat(window.isLocked(T0.plus(Duration.ofHours(23)).plus(Duration.ofMinutes(59)))).isFalse();
        assertThat(window.isLocked(T0.plus(Duration.ofHours(24)))).as("exactly 24 hours is the last moment").isFalse();
        assertThat(window.isLocked(T0.plus(Duration.ofHours(24)).plus(Duration.ofMinutes(1)))).isTrue();
    }

    @Test
    void undoing_and_finishing_again_at_hour_23_does_not_restart_the_clock_so_it_still_locks_at_hour_24_from_the_first_completion() {
        var afterFirst = EditWindow.open().after(finished(), T0);
        var at23 = T0.plus(Duration.ofHours(23));

        var duringCorrection = afterFirst.after(reopened(), at23);
        assertThat(duringCorrection.firstFinishedAt()).as("undoing does not clear the anchor").isEqualTo(T0);

        var recompleted = duringCorrection.after(finished(), at23.plus(Duration.ofMinutes(30)));
        assertThat(recompleted.firstFinishedAt()).as("finishing again does not move the anchor").isEqualTo(T0);
        assertThat(recompleted.editableUntil()).isEqualTo(T0.plus(Duration.ofHours(24)));
        assertThat(recompleted.isLocked(T0.plus(Duration.ofHours(23)).plus(Duration.ofMinutes(59)))).isFalse();
        assertThat(recompleted.isLocked(T0.plus(Duration.ofHours(24)).plus(Duration.ofMinutes(1)))).isTrue();
    }

    @Test
    void a_correction_left_open_at_the_deadline_is_locked_as_it_is() {
        var window = EditWindow.open().after(finished(), T0).after(reopened(), T0.plus(Duration.ofHours(23)));

        assertThat(window.isLocked(T0.plus(Duration.ofHours(25)))).isTrue();
    }

    @Test
    void an_unlock_grants_24_hours_from_the_unlock_and_never_shortens_the_window() {
        var locked = EditWindow.open().after(finished(), T0);
        var unlockAt = T0.plus(Duration.ofHours(30));
        var unlocked = locked.unlocked(unlockAt);

        assertThat(unlocked.firstFinishedAt()).isEqualTo(T0);
        assertThat(unlocked.editableUntil()).isEqualTo(unlockAt.plus(Duration.ofHours(24)));
        assertThat(unlocked.isLocked(unlockAt.plus(Duration.ofHours(23)))).isFalse();
        assertThat(unlocked.isLocked(unlockAt.plus(Duration.ofHours(25)))).isTrue();

        var early = locked.unlocked(T0.plus(Duration.ofHours(1)));
        assertThat(early.editableUntil()).as("never earlier than the first window (the use case unlocks only a locked match anyway)").isAfterOrEqualTo(T0.plus(Duration.ofHours(24)));
    }

    @Test
    void an_unlocked_match_finishing_again_does_not_start_a_new_clock() {
        var unlocked = EditWindow.open().after(finished(), T0).unlocked(T0.plus(Duration.ofHours(30)));
        var again = unlocked.after(reopened(), T0.plus(Duration.ofHours(31))).after(finished(), T0.plus(Duration.ofHours(32)));

        assertThat(again).isEqualTo(unlocked);
    }

    @Test
    void an_abandoned_match_counts_as_finished() {
        var score = MatchScoring.fold(SETUP, java.util.List.of(new EndMatch(EndMatch.Reason.ABANDONED)));
        var window = EditWindow.open().after(score, T0);

        assertThat(window.firstFinishedAt()).isEqualTo(T0);
        assertThat(window.isLocked(T0.plus(Duration.ofHours(25)))).isTrue();
    }
}
