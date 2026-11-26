package com.dsports.cricket.domain.scoring;

import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.model.MatchStatus;
import com.dsports.cricket.domain.rules.Delivery;
import com.dsports.cricket.domain.rules.InningsState.EndReason;
import com.dsports.cricket.domain.rules.InningsState.WaiverKind;
import com.dsports.cricket.domain.rules.MatchResultCalculator;
import com.dsports.cricket.domain.rules.RuleViolation;
import com.dsports.cricket.domain.rules.ScoringCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dsports.cricket.domain.scoring.GoldenMatch.SETUP;
import static com.dsports.cricket.domain.scoring.GoldenMatch.lion;
import static com.dsports.cricket.domain.scoring.GoldenMatch.tiger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchScoringTest {

    private static ScoreOutcome apply(MatchScore score, ScoreCommand c) {
        return MatchScoring.apply(SETUP, score, c);
    }

    private static void assertRejected(MatchScore score, ScoreCommand c, RuleViolation expected) {
        assertThat(apply(score, c)).isInstanceOfSatisfying(ScoreOutcome.Rejected.class, r -> assertThat(r.violation()).isEqualTo(expected));
    }

    @Test
    void the_first_innings_is_started_for_the_side_that_bats_first_with_the_match_rules_and_no_target() {
        var score = MatchScoring.fold(SETUP, List.of(GoldenMatch.lionsInnings().get(0)));
        var first = score.current();

        assertThat(first.battingTeamId()).isEqualTo(GoldenMatch.LIONS);
        assertThat(first.bowlingTeamId()).isEqualTo(GoldenMatch.TIGERS);
        assertThat(first.overs()).isEqualTo(2);
        assertThat(first.battingXi()).isEqualTo(SETUP.xiA());
        assertThat(first.target()).isNull();
        assertThat(score.status()).isEqualTo(MatchStatus.IN_PROGRESS);
    }

    @Test
    void the_hand_scored_innings_gives_the_innings_break_and_the_target() {
        var score = MatchScoring.fold(SETUP, GoldenMatch.lionsInnings());

        assertThat(score.current().runs()).isEqualTo(22);
        assertThat(score.current().wickets()).isEqualTo(2);
        assertThat(score.status()).isEqualTo(MatchStatus.INNINGS_BREAK);
        assertThat(score.result()).isEmpty();
    }

    @Test
    void the_second_innings_is_the_other_side_chasing_the_first_innings_total_plus_one() {
        var score = MatchScoring.fold(SETUP, List.of(GoldenMatch.lionsInnings(), GoldenMatch.tigersChase().subList(0, 1)).stream().flatMap(List::stream).toList());
        var second = score.current();

        assertThat(score.innings()).hasSize(2);
        assertThat(second.battingTeamId()).isEqualTo(GoldenMatch.TIGERS);
        assertThat(second.battingXi()).isEqualTo(SETUP.xiB());
        assertThat(second.target()).isEqualTo(23);
        assertThat(score.status()).isEqualTo(MatchStatus.IN_PROGRESS);
    }

    @Test
    void the_whole_match_is_won_off_the_last_ball_and_the_result_is_computed() {
        var score = MatchScoring.fold(SETUP, GoldenMatch.wholeMatch());

        assertThat(score.status()).isEqualTo(MatchStatus.COMPLETED);
        assertThat(score.current().endReason()).isEqualTo(EndReason.TARGET_REACHED);
        var result = score.result().orElseThrow();
        assertThat(result.type()).isEqualTo(MatchResultCalculator.Type.WON_BY_WICKETS);
        assertThat(result.winnerTeamId()).isEqualTo(GoldenMatch.TIGERS);
        assertThat(result.margin()).isEqualTo(10);
        assertThat(result.ballsRemaining()).isZero();
    }

    @Test
    void the_status_follows_the_innings() {
        var statuses = new java.util.ArrayList<MatchStatus>();
        statuses.add(MatchScore.empty().status());
        var all = GoldenMatch.wholeMatch();
        int firstInnings = GoldenMatch.lionsInnings().size();
        for (int i = 1; i <= all.size(); i++) {
            statuses.add(MatchScoring.fold(SETUP, all.subList(0, i)).status());
        }
        assertThat(statuses.get(0)).isEqualTo(MatchStatus.TOSS_DONE);
        assertThat(statuses.get(1)).isEqualTo(MatchStatus.IN_PROGRESS);
        assertThat(statuses.subList(1, firstInnings)).containsOnly(MatchStatus.IN_PROGRESS);
        assertThat(statuses.get(firstInnings)).isEqualTo(MatchStatus.INNINGS_BREAK);
        assertThat(statuses.get(firstInnings + 1)).isEqualTo(MatchStatus.IN_PROGRESS);
        assertThat(statuses.get(statuses.size() - 1)).isEqualTo(MatchStatus.COMPLETED);
    }

    @Test
    void an_innings_cannot_start_while_the_last_is_unfinished_or_after_both_are_played() {
        var openers = new ScoreCommand.StartInnings(tiger(1), tiger(2), lion(1));
        var midFirst = MatchScoring.fold(SETUP, GoldenMatch.lionsInnings().subList(0, 5));
        assertRejected(midFirst, openers, RuleViolation.PREVIOUS_INNINGS_NOT_COMPLETE);
        assertRejected(midFirst, GoldenMatch.lionsInnings().get(0), RuleViolation.PREVIOUS_INNINGS_NOT_COMPLETE);

        var finished = MatchScoring.fold(SETUP, GoldenMatch.wholeMatch());
        assertRejected(finished, openers, RuleViolation.ALL_INNINGS_PLAYED);
    }

    @Test
    void an_innings_needs_the_toss_and_both_xis() {
        var noToss = new MatchSetup(2, MatchRules.standard(), GoldenMatch.LIONS, GoldenMatch.TIGERS, null, SETUP.xiA(), SETUP.xiB());
        assertThat(MatchScoring.apply(noToss, MatchScore.empty(), GoldenMatch.lionsInnings().get(0))).isInstanceOfSatisfying(ScoreOutcome.Rejected.class,
            r -> assertThat(r.violation()).isEqualTo(RuleViolation.INVALID_SETUP));
        var noXi = new MatchSetup(2, MatchRules.standard(), GoldenMatch.LIONS, GoldenMatch.TIGERS, GoldenMatch.LIONS, List.of(lion(1)), SETUP.xiB());
        assertThat(MatchScoring.apply(noXi, MatchScore.empty(), GoldenMatch.lionsInnings().get(0))).isInstanceOfSatisfying(ScoreOutcome.Rejected.class,
            r -> assertThat(r.violation()).isEqualTo(RuleViolation.INVALID_SETUP));
    }

    @Test
    void the_openers_and_the_bowler_must_come_from_the_right_xis() {
        assertRejected(MatchScore.empty(), new ScoreCommand.StartInnings(tiger(1), tiger(2), tiger(3)), RuleViolation.INVALID_OPENERS);
        assertRejected(MatchScore.empty(), new ScoreCommand.StartInnings(lion(1), lion(2), lion(3)), RuleViolation.BOWLER_NOT_IN_XI);
    }

    @Test
    void nothing_can_be_played_before_an_innings_starts() {
        assertRejected(MatchScore.empty(), new ScoreCommand.Play(new ScoringCommand.Deliver(Delivery.dot())), RuleViolation.INNINGS_NOT_STARTED);
    }

    @Test
    void the_innings_engines_refusals_pass_through_with_their_codes() {
        var started = MatchScoring.fold(SETUP, GoldenMatch.lionsInnings().subList(0, 1));
        assertRejected(started, new ScoreCommand.Play(new ScoringCommand.Deliver(Delivery.runs(8))), RuleViolation.RUNS_OUT_OF_RANGE);
        assertRejected(started, new ScoreCommand.Play(new ScoringCommand.ReviseTarget(10)), RuleViolation.NO_TARGET_TO_REVISE);
    }

    @Test
    void waivers_come_back_with_the_command_that_caused_them() {
        var tiny = new MatchSetup(2, MatchRules.standard(), GoldenMatch.LIONS, GoldenMatch.TIGERS, GoldenMatch.LIONS, SETUP.xiA(), List.of(tiger(1), tiger(2)));
        var score = MatchScoring.fold(tiny, List.of(new ScoreCommand.StartInnings(lion(1), lion(2), tiger(1))));
        for (int i = 0; i < 6; i++) {
            score = ((ScoreOutcome.Accepted) MatchScoring.apply(tiny, score, new ScoreCommand.Play(new ScoringCommand.Deliver(Delivery.dot())))).score();
        }
        var outcome = (ScoreOutcome.Accepted) MatchScoring.apply(tiny, score, new ScoreCommand.Play(new ScoringCommand.SelectBowler(tiger(2))));
        assertThat(outcome.waivers()).isEmpty();
        assertThat(outcome.score().current().waivers()).isEmpty();
    }

    // ---- ending the match by hand -------------------------------------------------------------------------------

    @Test
    void a_match_can_be_abandoned_or_have_no_result_at_any_point_before_it_is_complete() {
        var mid = MatchScoring.fold(SETUP, GoldenMatch.lionsInnings().subList(0, 5));
        var abandoned = ((ScoreOutcome.Accepted) apply(mid, new ScoreCommand.EndMatch(ScoreCommand.EndMatch.Reason.ABANDONED))).score();
        assertThat(abandoned.status()).isEqualTo(MatchStatus.ABANDONED);
        assertThat(abandoned.isFinished()).isTrue();
        assertThat(abandoned.result()).isEmpty();

        var noResult = ((ScoreOutcome.Accepted) apply(MatchScore.empty(), new ScoreCommand.EndMatch(ScoreCommand.EndMatch.Reason.NO_RESULT))).score();
        assertThat(noResult.status()).isEqualTo(MatchStatus.NO_RESULT);
        var atBreak = MatchScoring.fold(SETUP, GoldenMatch.lionsInnings());
        assertThat(((ScoreOutcome.Accepted) apply(atBreak, new ScoreCommand.EndMatch(ScoreCommand.EndMatch.Reason.NO_RESULT))).score().status()).isEqualTo(MatchStatus.NO_RESULT);
    }

    @Test
    void once_the_match_is_ended_nothing_else_is_accepted() {
        var ended = ((ScoreOutcome.Accepted) apply(MatchScore.empty(), new ScoreCommand.EndMatch(ScoreCommand.EndMatch.Reason.ABANDONED))).score();

        assertRejected(ended, GoldenMatch.lionsInnings().get(0), RuleViolation.MATCH_ENDED);
        assertRejected(ended, new ScoreCommand.Play(new ScoringCommand.Deliver(Delivery.dot())), RuleViolation.MATCH_ENDED);
        assertRejected(ended, new ScoreCommand.EndMatch(ScoreCommand.EndMatch.Reason.NO_RESULT), RuleViolation.MATCH_ENDED);
    }

    @Test
    void a_completed_match_cannot_be_ended_and_a_reason_is_required() {
        var done = MatchScoring.fold(SETUP, GoldenMatch.wholeMatch());
        assertRejected(done, new ScoreCommand.EndMatch(ScoreCommand.EndMatch.Reason.ABANDONED), RuleViolation.MATCH_COMPLETE);
        assertRejected(MatchScore.empty(), new ScoreCommand.EndMatch(null), RuleViolation.INVALID_SETUP);
    }

    // ---- misc ---------------------------------------------------------------------------------------------------

    @Test
    void play_refuses_to_wrap_the_start_of_an_innings_and_types_are_named_for_the_log() {
        assertThatThrownBy(() -> new ScoreCommand.Play(new ScoringCommand.StartInnings(MatchRules.standard(), 2, GoldenMatch.LIONS, GoldenMatch.TIGERS,
            SETUP.xiA(), SETUP.xiB(), lion(1), lion(2), tiger(1), null))).isInstanceOf(IllegalArgumentException.class);
        assertThat(GoldenMatch.wholeMatch().stream().map(ScoreCommand::type).distinct()).contains(ScoreCommand.Type.START_INNINGS, ScoreCommand.Type.DELIVER,
            ScoreCommand.Type.SELECT_BATTER, ScoreCommand.Type.SELECT_BOWLER, ScoreCommand.Type.CHANGE_BOWLER, ScoreCommand.Type.RETIRE_BATTER);
        assertThat(new ScoreCommand.EndMatch(ScoreCommand.EndMatch.Reason.ABANDONED).type()).isEqualTo(ScoreCommand.Type.END_MATCH);
        assertThat(new ScoreCommand.Play(new ScoringCommand.ReviseTarget(5)).type()).isEqualTo(ScoreCommand.Type.REVISE_TARGET);
        assertThat(new ScoreCommand.Play(new ScoringCommand.EndInnings(EndReason.DECLARED)).type()).isEqualTo(ScoreCommand.Type.END_INNINGS);
    }

    @Test
    void folding_a_command_that_is_not_accepted_is_an_error() {
        assertThatThrownBy(() -> MatchScoring.fold(SETUP, List.of(new ScoreCommand.Play(new ScoringCommand.Deliver(Delivery.dot())))))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_waiver_kind_exists_for_both_last_resorts() {
        assertThat(WaiverKind.values()).containsExactly(WaiverKind.OVER_LIMIT, WaiverKind.CONSECUTIVE_OVER);
    }
}
