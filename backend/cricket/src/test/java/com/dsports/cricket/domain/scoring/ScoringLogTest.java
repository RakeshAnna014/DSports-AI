package com.dsports.cricket.domain.scoring;

import com.dsports.cricket.domain.model.MatchStatus;
import com.dsports.cricket.domain.rules.Delivery;
import com.dsports.cricket.domain.rules.MatchResultCalculator;
import com.dsports.cricket.domain.rules.ScoringCommand;
import com.dsports.cricket.domain.scoring.ScoringLog.UndoMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.dsports.cricket.domain.scoring.GoldenMatch.SETUP;
import static org.assertj.core.api.Assertions.assertThat;

/** Undo is an event: it voids commands and the state is the fold of what is left. These tests check that for every prefix of the golden match. */
class ScoringLogTest {

    private static MatchScore score(List<ScoreCommand> commands) {
        return MatchScoring.fold(SETUP, commands);
    }

    private static List<LogEntry> withUndo(List<LogEntry> entries, List<Integer> voids) {
        var all = new ArrayList<>(entries);
        all.add(LogEntry.undo(entries.size() + 1, voids, UUID.randomUUID(), GoldenMatch.lion(1), GoldenMatch.T0.plusSeconds(1000)));
        return all;
    }

    private static MatchScore afterUndo(List<LogEntry> entries, UndoMode mode) {
        return score(ScoringLog.commands(withUndo(entries, ScoringLog.planUndo(entries, mode))));
    }

    // ---- the effective log -------------------------------------------------------------------------------------

    @Test
    void an_undo_voids_commands_and_deletes_nothing() {
        var entries = GoldenMatch.log(GoldenMatch.lionsInnings());
        int n = entries.size();
        var after = withUndo(entries, List.of(n));

        assertThat(after).hasSize(n + 1);
        assertThat(ScoringLog.effective(after)).hasSize(n - 1);
        assertThat(ScoringLog.effective(after)).extracting(LogEntry::seq).doesNotContain(n);
    }

    @Test
    void unlock_entries_and_undo_entries_are_not_commands() {
        var entries = withUndo(GoldenMatch.log(GoldenMatch.lionsInnings()), List.of(21));
        var all = new ArrayList<>(entries);
        all.add(LogEntry.unlock(23, "scorer phone died", UUID.randomUUID(), GoldenMatch.lion(9), GoldenMatch.T0));

        assertThat(ScoringLog.effective(all)).hasSize(20).allMatch(e -> e.kind() == LogEntry.Kind.COMMAND);
    }

    // ---- planning an undo --------------------------------------------------------------------------------------

    private static List<LogEntry> prefix(int commands) {
        return GoldenMatch.log(GoldenMatch.lionsInnings().subList(0, commands));
    }

    @Test
    void command_mode_takes_off_exactly_the_latest_command() {
        assertThat(ScoringLog.planUndo(prefix(12), UndoMode.COMMAND)).containsExactly(12);
        assertThat(ScoringLog.planUndo(prefix(1), UndoMode.COMMAND)).containsExactly(1);
    }

    @Test
    void ball_mode_takes_the_choices_made_after_a_ball_together_with_the_ball() {
        assertThat(ScoringLog.planUndo(prefix(12), UndoMode.BALL)).as("new bowler after the last ball of the over").containsExactly(12, 11);
        assertThat(ScoringLog.planUndo(prefix(10), UndoMode.BALL)).as("new batter after a wicket").containsExactly(10, 9);
        assertThat(ScoringLog.planUndo(prefix(8), UndoMode.BALL)).as("new batter after a run out").containsExactly(8, 7);
    }

    @Test
    void ball_mode_takes_a_retirement_and_its_replacement_together_and_a_lone_command_alone() {
        assertThat(ScoringLog.planUndo(prefix(16), UndoMode.BALL)).containsExactly(16, 15);
        assertThat(ScoringLog.planUndo(prefix(17), UndoMode.BALL)).as("the bowler change").containsExactly(17);
        assertThat(ScoringLog.planUndo(prefix(14), UndoMode.BALL)).as("a plain ball").containsExactly(14);
    }

    @Test
    void ball_mode_never_goes_past_the_start_of_an_innings() {
        assertThat(ScoringLog.planUndo(prefix(1), UndoMode.BALL)).containsExactly(1);
        var chaseStart = GoldenMatch.log(List.of(GoldenMatch.lionsInnings().get(0), GoldenMatch.lionsInnings().get(1)));
        assertThat(ScoringLog.planUndo(chaseStart, UndoMode.BALL)).containsExactly(2);
        var wholeUpToChaseStart = GoldenMatch.log(GoldenMatch.wholeMatch().subList(0, GoldenMatch.lionsInnings().size() + 1));
        assertThat(ScoringLog.planUndo(wholeUpToChaseStart, UndoMode.BALL)).containsExactly(wholeUpToChaseStart.size());
    }

    @Test
    void with_nothing_left_there_is_nothing_to_undo() {
        assertThat(ScoringLog.planUndo(List.of(), UndoMode.BALL)).isEmpty();
        var entries = GoldenMatch.log(GoldenMatch.lionsInnings().subList(0, 1));
        assertThat(ScoringLog.planUndo(withUndo(entries, List.of(1)), UndoMode.COMMAND)).isEmpty();
    }

    @Test
    void undoing_again_takes_off_the_next_command_down() {
        var entries = GoldenMatch.log(GoldenMatch.lionsInnings().subList(0, 6));
        var once = withUndo(entries, ScoringLog.planUndo(entries, UndoMode.COMMAND));
        assertThat(ScoringLog.planUndo(once, UndoMode.COMMAND)).containsExactly(5);
        var twice = withUndo(once, List.of(5));
        assertThat(ScoringLog.planUndo(twice, UndoMode.COMMAND)).containsExactly(4);
        assertThat(score(ScoringLog.commands(twice))).isEqualTo(score(GoldenMatch.lionsInnings().subList(0, 4)));
    }

    // ---- the state after an undo equals the state before the commands -----------------------------------------

    @Test
    void for_every_prefix_of_the_golden_match_one_undo_gives_exactly_the_state_before_the_last_command() {
        var all = GoldenMatch.wholeMatch();
        for (int k = 1; k <= all.size(); k++) {
            var entries = GoldenMatch.log(all.subList(0, k));
            assertThat(afterUndo(entries, UndoMode.COMMAND)).as("undo of command %d", k).isEqualTo(score(all.subList(0, k - 1)));
        }
    }

    @Test
    void for_every_prefix_ball_mode_gives_the_state_before_the_ball_and_what_followed_it() {
        var all = GoldenMatch.wholeMatch();
        for (int k = 1; k <= all.size(); k++) {
            var entries = GoldenMatch.log(all.subList(0, k));
            int taken = ScoringLog.planUndo(entries, UndoMode.BALL).size();
            assertThat(afterUndo(entries, UndoMode.BALL)).as("undo ball at command %d", k).isEqualTo(score(all.subList(0, k - taken)));
        }
    }

    @Test
    void undoing_everything_gets_back_to_the_toss() {
        var entries = GoldenMatch.log(GoldenMatch.wholeMatch());
        int n = entries.size();
        for (int i = 0; i < n; i++) {
            entries = withUndo(entries, ScoringLog.planUndo(entries, UndoMode.COMMAND));
        }
        assertThat(ScoringLog.effective(entries)).isEmpty();
        assertThat(score(ScoringLog.commands(entries)).status()).isEqualTo(MatchStatus.TOSS_DONE);
    }

    // ---- across an over boundary -------------------------------------------------------------------------------

    @Test
    void across_an_over_boundary_undo_unwinds_ball_one_then_the_bowler_then_the_last_ball_of_the_previous_over() {
        var all = GoldenMatch.lionsInnings();
        var upToBall1 = GoldenMatch.log(all.subList(0, 13));
        var before = score(all.subList(0, 13));
        assertThat(before.current().oversText()).isEqualTo("1.1");

        var first = withUndo(upToBall1, ScoringLog.planUndo(upToBall1, UndoMode.COMMAND));
        var afterBall = score(ScoringLog.commands(first));
        assertThat(afterBall.current().oversText()).isEqualTo("1.0");
        assertThat(afterBall.current().needsBowler()).isFalse();

        var second = withUndo(first, ScoringLog.planUndo(first, UndoMode.COMMAND));
        var afterBowler = score(ScoringLog.commands(second));
        assertThat(afterBowler.current().needsBowler()).isTrue();
        assertThat(afterBowler.current().currentBowler()).isEqualTo(GoldenMatch.tiger(1));

        var third = withUndo(second, ScoringLog.planUndo(second, UndoMode.COMMAND));
        var afterLastBall = score(ScoringLog.commands(third));
        assertThat(afterLastBall.current().oversText()).isEqualTo("0.5");
        assertThat(afterLastBall.current().needsBowler()).isFalse();
        assertThat(afterLastBall.current().previousOverBowler()).isNull();
        assertThat(afterLastBall.current().bowler(GoldenMatch.tiger(1)).orElseThrow().creditedOvers()).isZero();
        assertThat(afterLastBall.current().striker()).isEqualTo(GoldenMatch.lion(4));
        assertThat(afterLastBall.current().nonStriker()).isEqualTo(GoldenMatch.lion(2));
        assertThat(ScoringLog.reopens(afterBowler, afterLastBall)).as("an over boundary is not a reopening").isFalse();
    }

    // ---- across an innings end and a finished match -----------------------------------------------------------

    @Test
    void undoing_the_winning_ball_reopens_the_match_and_removes_the_result() {
        var entries = GoldenMatch.log(GoldenMatch.wholeMatch());
        var before = score(ScoringLog.commands(entries));
        var after = afterUndo(entries, UndoMode.COMMAND);

        assertThat(before.status()).isEqualTo(MatchStatus.COMPLETED);
        assertThat(before.result()).isPresent();
        assertThat(after.status()).isEqualTo(MatchStatus.IN_PROGRESS);
        assertThat(after.result()).isEmpty();
        assertThat(after.current().runs()).isEqualTo(20);
        assertThat(ScoringLog.reopens(before, after)).isTrue();
    }

    @Test
    void undoing_the_start_of_the_second_innings_goes_back_to_the_innings_break_and_that_reopens_it() {
        var entries = GoldenMatch.log(GoldenMatch.wholeMatch().subList(0, GoldenMatch.lionsInnings().size() + 1));
        var before = score(ScoringLog.commands(entries));
        var after = afterUndo(entries, UndoMode.COMMAND);

        assertThat(after.status()).isEqualTo(MatchStatus.INNINGS_BREAK);
        assertThat(ScoringLog.reopens(before, after)).isTrue();
    }

    @Test
    void undoing_the_last_ball_of_the_first_innings_reopens_it_and_an_ordinary_ball_does_not() {
        var entries = GoldenMatch.log(GoldenMatch.lionsInnings());
        var before = score(ScoringLog.commands(entries));
        var after = afterUndo(entries, UndoMode.COMMAND);
        assertThat(before.status()).isEqualTo(MatchStatus.INNINGS_BREAK);
        assertThat(after.status()).isEqualTo(MatchStatus.IN_PROGRESS);
        assertThat(ScoringLog.reopens(before, after)).isTrue();

        var mid = GoldenMatch.log(GoldenMatch.lionsInnings().subList(0, 14));
        assertThat(ScoringLog.reopens(score(ScoringLog.commands(mid)), afterUndo(mid, UndoMode.COMMAND))).isFalse();
    }

    @Test
    void undoing_a_match_end_reopens_the_match() {
        var commands = new ArrayList<>(GoldenMatch.lionsInnings().subList(0, 5));
        commands.add(new ScoreCommand.EndMatch(ScoreCommand.EndMatch.Reason.ABANDONED));
        var entries = GoldenMatch.log(commands);
        var before = score(commands);
        var after = afterUndo(entries, UndoMode.COMMAND);

        assertThat(before.status()).isEqualTo(MatchStatus.ABANDONED);
        assertThat(after.status()).isEqualTo(MatchStatus.IN_PROGRESS);
        assertThat(ScoringLog.reopens(before, after)).isTrue();
    }

    @Test
    void the_target_and_the_result_follow_a_correction_to_the_first_innings() {
        var corrected = new ArrayList<>(GoldenMatch.lionsInnings().subList(0, GoldenMatch.lionsInnings().size() - 1));
        corrected.add(new ScoreCommand.Play(new ScoringCommand.Deliver(Delivery.boundary(4))));
        corrected.addAll(GoldenMatch.tigersChase());
        var score = score(corrected);

        assertThat(score.innings().get(0).runs()).isEqualTo(25);
        assertThat(score.innings().get(1).target()).isEqualTo(26);
        assertThat(score.status()).isEqualTo(MatchStatus.COMPLETED);
        var result = score.result().orElseThrow();
        assertThat(result.type()).isEqualTo(MatchResultCalculator.Type.WON_BY_RUNS);
        assertThat(result.winnerTeamId()).isEqualTo(GoldenMatch.LIONS);
        assertThat(result.margin()).isEqualTo(2);
    }
}
