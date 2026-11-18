package com.dsports.cricket.application;

import com.dsports.cricket.application.result.ScoringView;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.model.MatchStatus;
import com.dsports.cricket.domain.rules.Delivery;
import com.dsports.cricket.domain.rules.ScoringCommand;
import com.dsports.cricket.domain.scoring.GoldenMatch;
import com.dsports.cricket.domain.scoring.LogEntry;
import com.dsports.cricket.domain.scoring.MatchScoring;
import com.dsports.cricket.domain.scoring.ScoreCommand;
import com.dsports.cricket.domain.scoring.ScoringLog;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.dsports.cricket.application.ScoringFixture.ADMIN;
import static com.dsports.cricket.application.ScoringFixture.OPPONENT;
import static com.dsports.cricket.application.ScoringFixture.ORGANISER;
import static com.dsports.cricket.application.ScoringFixture.SCORER;
import static com.dsports.cricket.application.ScoringFixture.STRANGER;
import static com.dsports.cricket.application.ScoringFixture.T0;
import static com.dsports.cricket.domain.scoring.GoldenMatch.lion;
import static com.dsports.cricket.domain.scoring.GoldenMatch.tiger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoringUseCaseTest {

    private static ScoreCommand dot() {
        return new ScoreCommand.Play(new ScoringCommand.Deliver(Delivery.dot()));
    }

    private static ScoreCommand ball(Delivery d) {
        return new ScoreCommand.Play(new ScoringCommand.Deliver(d));
    }

    private static CricketDomainException failure(Runnable action) {
        return org.assertj.core.api.Assertions.catchThrowableOfType(CricketDomainException.class, action::run);
    }

    private static void assertCode(Runnable action, CricketErrorCode code) {
        assertThat(failure(action)).isNotNull().extracting(CricketDomainException::getErrorCode).isEqualTo(code);
    }

    // ---- scoring a whole match -----------------------------------------------------------------------------------

    @Test
    void the_golden_match_scored_through_the_use_case_gives_the_hand_scored_scorecard_with_names() {
        var fx = new ScoringFixture();
        var view = fx.scoreAll(GoldenMatch.wholeMatch()).view();

        assertThat(view.seq()).isEqualTo(GoldenMatch.wholeMatch().size());
        assertThat(view.matchStatus()).isEqualTo("COMPLETED");
        assertThat(view.result().text()).isEqualTo("Tigers won by 10 wickets (0 balls left)");
        var first = view.innings().get(0);
        assertThat(first.battingTeam().name()).isEqualTo("Lions");
        assertThat(first.runs()).isEqualTo(22);
        assertThat(first.wickets()).isEqualTo(2);
        assertThat(first.overs()).isEqualTo("2.0");
        assertThat(first.batters()).extracting(b -> b.player().name(), b -> b.runs(), b -> b.balls(), ScoringView.Batter::howOut, ScoringView.Batter::notOut)
            .containsExactly(
                org.assertj.core.api.Assertions.tuple("Lion 1", 2, 2, "run out (Tiger 3)", false),
                org.assertj.core.api.Assertions.tuple("Lion 2", 14, 6, "not out", true),
                org.assertj.core.api.Assertions.tuple("Lion 3", 0, 1, "b Tiger 1", false),
                org.assertj.core.api.Assertions.tuple("Lion 4", 2, 2, "retired hurt", true),
                org.assertj.core.api.Assertions.tuple("Lion 5", 1, 2, "not out", true));
        assertThat(first.bowlers()).extracting(b -> b.player().name(), ScoringView.Bowler::overs, ScoringView.Bowler::runs, ScoringView.Bowler::wickets)
            .containsExactly(org.assertj.core.api.Assertions.tuple("Tiger 1", "1.0", 10, 1), org.assertj.core.api.Assertions.tuple("Tiger 2", "0.2", 3, 0),
                org.assertj.core.api.Assertions.tuple("Tiger 3", "0.4", 8, 0));
        assertThat(first.fallOfWickets()).extracting(f -> f.number(), f -> f.runs(), f -> f.batter().name(), ScoringView.Fall::overs)
            .containsExactly(org.assertj.core.api.Assertions.tuple(1, 11, "Lion 1", "0.4"), org.assertj.core.api.Assertions.tuple(2, 11, "Lion 3", "0.5"));
        var second = view.innings().get(1);
        assertThat(second.battingTeam().name()).isEqualTo("Tigers");
        assertThat(second.target()).isEqualTo(23);
        assertThat(second.runs()).isEqualTo(23);
        assertThat(second.endReason()).isEqualTo("TARGET_REACHED");
        assertThat(second.runsNeeded()).isZero();
    }

    @Test
    void the_snapshot_and_the_match_status_follow_every_command_and_equal_the_fold_of_the_log() {
        var fx = new ScoringFixture();
        var commands = GoldenMatch.wholeMatch();
        for (int i = 0; i < commands.size(); i++) {
            fx.score(commands.get(i));
            var snapshot = fx.store.snapshots.get(fx.id());
            var expected = MatchScoring.fold(GoldenMatch.SETUP, ScoringLog.commands(fx.store.entriesOf(fx.id())));
            assertThat(snapshot.seq()).isEqualTo(i + 1);
            assertThat(snapshot.score()).as("snapshot after %d", i + 1).isEqualTo(expected);
            assertThat(fx.store.statuses.get(fx.id())).isEqualTo(expected.status());
        }
        assertThat(fx.store.statuses.get(fx.id())).isEqualTo(MatchStatus.COMPLETED);
    }

    @Test
    void a_fresh_match_has_nothing_scored_and_says_what_comes_first() {
        var view = new ScoringFixture().view();

        assertThat(view.seq()).isZero();
        assertThat(view.matchStatus()).isEqualTo("TOSS_DONE");
        assertThat(view.innings()).isEmpty();
        assertThat(view.canUndo().allowed()).isFalse();
        assertThat(view.canUndo().blockedReason()).isEqualTo("NOTHING_TO_UNDO");
        assertThat(view.locked()).isFalse();
    }

    @Test
    void scoring_cannot_start_before_the_toss_and_both_xis_but_a_match_can_still_be_called_off() {
        var fx = new ScoringFixture(2, MatchRules.standard(), 11, 11, false);

        assertCode(() -> fx.useCase.submit(ORGANISER, fx.id(), UUID.randomUUID(), 0, ScoringFixture.lionsOpen()).block(), CricketErrorCode.MATCH_NOT_READY);
        assertThat(fx.store.entriesOf(fx.id())).isEmpty();
        var off = fx.useCase.submit(ORGANISER, fx.id(), UUID.randomUUID(), 0, new ScoreCommand.EndMatch(ScoreCommand.EndMatch.Reason.ABANDONED)).block();
        assertThat(off.view().matchStatus()).isEqualTo("ABANDONED");
        assertThat(off.view().result().text()).isEqualTo("Match abandoned");
    }

    // ---- two scorers: conflicts and retries ------------------------------------------------------------------------

    @Test
    void a_command_made_from_an_old_view_is_a_conflict_that_says_where_the_match_is_now() {
        var fx = new ScoringFixture();
        fx.score(ScoringFixture.lionsOpen());
        fx.score(dot());

        var conflict = failure(() -> fx.useCase.submit(SCORER, fx.id(), UUID.randomUUID(), 1, dot()).block());

        assertThat(conflict.getErrorCode()).isEqualTo(CricketErrorCode.SCORE_CONFLICT);
        var details = (ScoringView.Conflict) conflict.getDetails();
        assertThat(details.currentSeq()).isEqualTo(2);
        assertThat(details.view().seq()).isEqualTo(2);
        assertThat(details.eventsSince()).extracting(ScoringView.Event::seq).containsExactly(2);
        assertThat(details.eventsSince().get(0).text()).isEqualTo("no run");
        assertThat(details.eventsSince().get(0).by().name()).isEqualTo("Olive Organiser");
        assertThat(fx.store.entriesOf(fx.id())).hasSize(2);
    }

    @Test
    void a_scorer_who_is_up_to_date_can_score_after_the_organiser() {
        var fx = new ScoringFixture();
        fx.score(ScoringFixture.lionsOpen());

        var outcome = fx.useCase.submit(SCORER, fx.id(), UUID.randomUUID(), 1, dot()).block();

        assertThat(outcome.eventSeq()).isEqualTo(2);
        assertThat(fx.store.entriesOf(fx.id()).get(1).actorId()).isEqualTo(SCORER.id());
        assertThat(outcome.view().recentEvents().get(1).by().name()).isEqualTo("Sam Scorer");
    }

    @Test
    void a_retry_of_a_command_already_in_the_log_is_answered_as_done_even_from_an_old_view_and_writes_nothing() {
        var fx = new ScoringFixture();
        var id = UUID.randomUUID();
        var first = fx.useCase.submit(ORGANISER, fx.id(), id, 0, ScoringFixture.lionsOpen()).block();
        fx.seq = first.eventSeq();
        fx.score(dot());
        fx.score(dot());

        var retry = fx.useCase.submit(ORGANISER, fx.id(), id, 0, ScoringFixture.lionsOpen()).block();

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.eventSeq()).isEqualTo(1);
        assertThat(retry.view().seq()).as("the view is the current one").isEqualTo(3);
        assertThat(fx.store.entriesOf(fx.id())).hasSize(3);
        assertThat(first.replayed()).isFalse();
    }

    @Test
    void the_same_client_event_id_for_a_different_command_is_refused() {
        var fx = new ScoringFixture();
        var id = UUID.randomUUID();
        fx.useCase.submit(ORGANISER, fx.id(), id, 0, ScoringFixture.lionsOpen()).block();

        assertCode(() -> fx.useCase.submit(ORGANISER, fx.id(), id, 1, dot()).block(), CricketErrorCode.CLIENT_EVENT_REUSED);
        assertThat(fx.store.entriesOf(fx.id())).hasSize(1);
    }

    @Test
    void a_command_the_engine_refuses_is_a_scoring_rejection_with_its_rule_and_writes_nothing() {
        var fx = new ScoringFixture();
        fx.score(ScoringFixture.lionsOpen());

        var refused = failure(() -> fx.useCase.submit(ORGANISER, fx.id(), UUID.randomUUID(), 1, ball(Delivery.runs(8))).block());

        assertThat(refused.getErrorCode()).isEqualTo(CricketErrorCode.SCORING_REJECTED);
        assertThat(((ScoringView.Rejection) refused.getDetails()).violation()).isEqualTo("RUNS_OUT_OF_RANGE");
        assertThat(fx.store.entriesOf(fx.id())).hasSize(1);
        assertThat(fx.store.snapshots.get(fx.id()).seq()).isEqualTo(1);
    }

    @Test
    void a_rejection_is_not_stored_so_the_same_id_can_be_used_again_for_the_corrected_ball() {
        var fx = new ScoringFixture();
        fx.score(ScoringFixture.lionsOpen());
        var id = UUID.randomUUID();
        assertCode(() -> fx.useCase.submit(ORGANISER, fx.id(), id, 1, ball(Delivery.runs(8))).block(), CricketErrorCode.SCORING_REJECTED);

        var outcome = fx.useCase.submit(ORGANISER, fx.id(), id, 1, ball(Delivery.runs(2))).block();

        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.view().innings().get(0).runs()).isEqualTo(2);
    }

    // ---- who may do what -------------------------------------------------------------------------------------------

    @Test
    void the_organiser_and_an_added_scorer_may_score_a_team_member_is_forbidden_and_a_stranger_is_told_there_is_no_such_match() {
        var fx = new ScoringFixture();
        fx.useCase.state(ORGANISER, fx.id()).block();
        fx.useCase.state(SCORER, fx.id()).block();

        assertCode(() -> fx.useCase.state(OPPONENT, fx.id()).block(), CricketErrorCode.NOT_ALLOWED);
        assertCode(() -> fx.useCase.submit(OPPONENT, fx.id(), UUID.randomUUID(), 0, ScoringFixture.lionsOpen()).block(), CricketErrorCode.NOT_ALLOWED);
        assertCode(() -> fx.useCase.undo(OPPONENT, fx.id(), UUID.randomUUID(), 0, ScoringLog.UndoMode.BALL).block(), CricketErrorCode.NOT_ALLOWED);
        assertCode(() -> fx.useCase.state(STRANGER, fx.id()).block(), CricketErrorCode.MATCH_NOT_FOUND);
        assertCode(() -> fx.useCase.submit(STRANGER, fx.id(), UUID.randomUUID(), 0, ScoringFixture.lionsOpen()).block(), CricketErrorCode.MATCH_NOT_FOUND);
        assertCode(() -> fx.useCase.undo(STRANGER, fx.id(), UUID.randomUUID(), 0, ScoringLog.UndoMode.BALL).block(), CricketErrorCode.MATCH_NOT_FOUND);
        assertCode(() -> fx.useCase.state(ORGANISER, UUID.randomUUID()).block(), CricketErrorCode.MATCH_NOT_FOUND);
        assertThat(fx.store.entriesOf(fx.id())).isEmpty();
    }

    // ---- what the screen is told ------------------------------------------------------------------------------------

    private static List<ScoreCommand> first(int n) {
        return GoldenMatch.lionsInnings().subList(0, n);
    }

    @Test
    void the_over_strip_shows_the_balls_so_far_and_the_free_hit_changes_what_a_wicket_may_be() {
        var fx = new ScoringFixture();
        var afterNoBall = fx.scoreAll(first(4)).view();
        assertThat(afterNoBall.freeHit()).isTrue();
        assertThat(afterNoBall.allowedDismissals().get("NORMAL")).containsExactly("RUN_OUT", "OBSTRUCTING_FIELD", "HIT_BALL_TWICE");
        assertThat(afterNoBall.allowedDismissals().get("WIDE")).containsExactly("RUN_OUT", "OBSTRUCTING_FIELD");

        var afterBye = fx.scoreAll(GoldenMatch.lionsInnings().subList(4, 6)).view();
        assertThat(afterBye.freeHit()).isFalse();
        assertThat(afterBye.innings().get(0).thisOver()).containsExactly("Wd", "1", "Nb+4", "2", "B1");
        assertThat(afterBye.allowedDismissals().get("NORMAL")).hasSize(8);
        assertThat(afterBye.allowedDismissals().get("WIDE")).containsExactly("STUMPED", "HIT_WICKET", "RUN_OUT", "OBSTRUCTING_FIELD");
        assertThat(afterBye.allowedDismissals().get("NO_BALL")).containsExactly("RUN_OUT", "OBSTRUCTING_FIELD", "HIT_BALL_TWICE");
    }

    @Test
    void after_a_wicket_the_screen_is_told_a_batter_is_needed_and_who_may_come_in() {
        var fx = new ScoringFixture();
        var view = fx.scoreAll(first(7)).view();

        assertThat(view.needs()).isEqualTo("BATTER");
        assertThat(view.innings().get(0).striker()).isNull();
        assertThat(view.eligibleBatters()).hasSize(9);
        assertThat(view.eligibleBatters().get(0).player().name()).isEqualTo("Lion 3");
        assertThat(view.eligibleBatters()).allMatch(b -> !b.returning());
    }

    @Test
    void at_the_end_of_an_over_the_screen_is_told_a_bowler_is_needed_and_the_last_over_stays_on_the_strip_until_he_is_chosen() {
        var fx = new ScoringFixture();
        var view = fx.scoreAll(first(11)).view();

        assertThat(view.needs()).isEqualTo("BOWLER");
        assertThat(view.innings().get(0).thisOver()).containsExactly("Wd", "1", "Nb+4", "2", "B1", "1W", "W", "•");
        var tiger1 = view.bowlers().get(0);
        assertThat(tiger1.player().name()).isEqualTo("Tiger 1");
        assertThat(tiger1.canBowlNext()).isFalse();
        assertThat(tiger1.why()).isEqualTo("BOWLED_LAST_OVER");
        assertThat(view.bowlers().get(1).canBowlNext()).isTrue();
        assertThat(view.waiverRequired()).isFalse();
        assertThat(view.bowlers().get(0).limit()).isEqualTo(1);

        var next = fx.score(first(12).get(11)).view();
        assertThat(next.needs()).isNull();
        assertThat(next.innings().get(0).thisOver()).isEmpty();
    }

    @Test
    void a_retired_hurt_batter_is_offered_as_returning_with_his_score() {
        var fx = new ScoringFixture();
        var view = fx.scoreAll(first(15)).view();

        assertThat(view.needs()).isEqualTo("BATTER");
        var returning = view.eligibleBatters().stream().filter(ScoringView.BatterChoice::returning).findFirst().orElseThrow();
        assertThat(returning.player().name()).isEqualTo("Lion 4");
        assertThat(returning.note()).isEqualTo("retired hurt, 2 off 2");
    }

    @Test
    void a_bowler_may_be_replaced_mid_over_by_someone_who_has_not_bowled_in_it() {
        var fx = new ScoringFixture();
        var view = fx.scoreAll(first(14)).view();

        var uday = view.bowlers().stream().filter(b -> b.player().name().equals("Tiger 2")).findFirst().orElseThrow();
        var vik = view.bowlers().stream().filter(b -> b.player().name().equals("Tiger 3")).findFirst().orElseThrow();
        assertThat(uday.canReplaceMidOver()).as("he is bowling").isFalse();
        assertThat(vik.canReplaceMidOver()).isTrue();
        assertThat(view.bowlers().stream().filter(b -> b.player().name().equals("Tiger 1")).findFirst().orElseThrow().canReplaceMidOver()).as("bowled the last over").isFalse();
    }

    // ---- undo -----------------------------------------------------------------------------------------------------

    @Test
    void undo_ball_takes_off_the_last_ball_and_says_what_it_will_do_beforehand() {
        var fx = new ScoringFixture();
        var before = fx.scoreAll(first(13)).view();
        assertThat(before.canUndo().allowed()).isTrue();
        assertThat(before.canUndo().describe()).isEqualTo("Undo: 1 run");
        assertThat(before.canUndo().crossesInnings()).isFalse();

        var after = fx.undo(ScoringLog.UndoMode.BALL);

        assertThat(after.eventSeq()).isEqualTo(14);
        assertThat(after.view().innings().get(0).overs()).isEqualTo("1.0");
        assertThat(after.view().recentEvents().get(after.view().recentEvents().size() - 1).text()).isEqualTo("Undid: 1 run");
        assertThat(fx.store.entriesOf(fx.id()).get(13).kind()).isEqualTo(LogEntry.Kind.UNDO);
        assertThat(fx.store.entriesOf(fx.id())).as("nothing was deleted").hasSize(14);
    }

    @Test
    void across_an_over_boundary_the_next_undo_ball_takes_off_the_bowler_choice_and_the_last_ball_of_the_over_together() {
        var fx = new ScoringFixture();
        fx.scoreAll(first(13));
        fx.undo(ScoringLog.UndoMode.BALL);

        var preview = fx.view().canUndo();
        assertThat(preview.describe()).isEqualTo("Undo: no run");
        assertThat(preview.crossesInnings()).isFalse();
        var after = fx.undo(ScoringLog.UndoMode.BALL).view();

        assertThat(after.innings().get(0).overs()).isEqualTo("0.5");
        assertThat(after.needs()).isNull();
        assertThat(after.innings().get(0).bowler().name()).isEqualTo("Tiger 1");
        assertThat(after.innings().get(0).striker().name()).isEqualTo("Lion 4");
        assertThat(after.innings().get(0).bowlers().get(0).creditedOvers()).isZero();
    }

    @Test
    void undoing_the_winning_ball_reopens_the_match_removes_the_result_and_the_screen_warns_first() {
        var fx = new ScoringFixture();
        var done = fx.scoreAll(GoldenMatch.wholeMatch()).view();
        assertThat(done.canUndo().crossesInnings()).isTrue();

        var after = fx.undo(ScoringLog.UndoMode.BALL).view();

        assertThat(after.matchStatus()).isEqualTo("IN_PROGRESS");
        assertThat(after.result()).isNull();
        assertThat(after.innings().get(1).runs()).isEqualTo(20);
        assertThat(fx.store.statuses.get(fx.id())).isEqualTo(MatchStatus.IN_PROGRESS);
    }

    @Test
    void undoing_everything_goes_back_to_the_toss() {
        var fx = new ScoringFixture();
        fx.scoreAll(first(3));
        fx.undo(ScoringLog.UndoMode.COMMAND);
        fx.undo(ScoringLog.UndoMode.COMMAND);
        var view = fx.undo(ScoringLog.UndoMode.COMMAND).view();

        assertThat(view.matchStatus()).isEqualTo("TOSS_DONE");
        assertThat(view.innings()).isEmpty();
        assertCode(() -> fx.useCase.undo(ORGANISER, fx.id(), UUID.randomUUID(), fx.seq, ScoringLog.UndoMode.BALL).block(), CricketErrorCode.NOTHING_TO_UNDO);
        fx.scoreAll(first(2));
        assertThat(fx.view().innings().get(0).runs()).isEqualTo(1);
    }

    @Test
    void an_undo_retried_with_the_same_id_is_not_done_twice_and_a_stale_undo_is_a_conflict() {
        var fx = new ScoringFixture();
        fx.scoreAll(first(5));
        var id = UUID.randomUUID();
        var done = fx.useCase.undo(ORGANISER, fx.id(), id, 5, ScoringLog.UndoMode.BALL).block();
        var retry = fx.useCase.undo(ORGANISER, fx.id(), id, 5, ScoringLog.UndoMode.BALL).block();

        assertThat(done.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.eventSeq()).isEqualTo(done.eventSeq());
        assertThat(fx.store.entriesOf(fx.id())).hasSize(6);
        assertCode(() -> fx.useCase.undo(ORGANISER, fx.id(), UUID.randomUUID(), 5, ScoringLog.UndoMode.BALL).block(), CricketErrorCode.SCORE_CONFLICT);
        assertCode(() -> fx.useCase.undo(ORGANISER, fx.id(), id, 5, ScoringLog.UndoMode.COMMAND).block(), CricketErrorCode.CLIENT_EVENT_REUSED);
    }

    // ---- ending by hand ---------------------------------------------------------------------------------------------

    @Test
    void a_match_ended_by_hand_accepts_nothing_more_and_an_undo_reopens_it() {
        var fx = new ScoringFixture();
        fx.scoreAll(first(5));
        var ended = fx.score(new ScoreCommand.EndMatch(ScoreCommand.EndMatch.Reason.NO_RESULT));
        assertThat(ended.view().matchStatus()).isEqualTo("NO_RESULT");

        assertThat(failure(() -> fx.useCase.submit(ORGANISER, fx.id(), UUID.randomUUID(), fx.seq, dot()).block()).getErrorCode()).isEqualTo(CricketErrorCode.SCORING_REJECTED);
        var back = fx.undo(ScoringLog.UndoMode.COMMAND).view();
        assertThat(back.matchStatus()).isEqualTo("IN_PROGRESS");
        assertThat(back.canUndo().crossesInnings()).isFalse();
    }

    // ---- the 24-hour window -----------------------------------------------------------------------------------------

    @Test
    void a_finished_match_can_be_corrected_for_24_hours_from_the_first_completion_even_if_a_correction_completes_it_again() {
        var fx = new ScoringFixture();
        var winningBall = GoldenMatch.tigersChase().get(GoldenMatch.tigersChase().size() - 1);
        fx.scoreAll(GoldenMatch.wholeMatch());
        assertThat(fx.store.snapshots.get(fx.id()).window().firstFinishedAt()).isEqualTo(T0);

        fx.clock.now = T0.plus(Duration.ofHours(23));
        assertThat(fx.undo(ScoringLog.UndoMode.BALL).view().matchStatus()).isEqualTo("IN_PROGRESS");
        fx.clock.now = T0.plus(Duration.ofHours(23)).plus(Duration.ofMinutes(30));
        var recompleted = fx.score(winningBall).view();

        assertThat(recompleted.matchStatus()).isEqualTo("COMPLETED");
        assertThat(fx.store.snapshots.get(fx.id()).window().firstFinishedAt()).as("finishing again does not restart the clock").isEqualTo(T0);
        fx.clock.now = T0.plus(Duration.ofHours(23)).plus(Duration.ofMinutes(59));
        var late = fx.view();
        assertThat(late.locked()).isFalse();
        assertThat(late.canUndo().allowed()).isTrue();
        assertThat(late.editableUntil()).isEqualTo(T0.plus(Duration.ofHours(24)));

        fx.clock.now = T0.plus(Duration.ofHours(24)).plus(Duration.ofMinutes(1));
        var locked = fx.view();
        assertThat(locked.locked()).isTrue();
        assertThat(locked.canUndo().allowed()).isFalse();
        assertThat(locked.canUndo().blockedReason()).isEqualTo("LOCKED");
        assertCode(() -> fx.useCase.undo(ORGANISER, fx.id(), UUID.randomUUID(), fx.seq, ScoringLog.UndoMode.BALL).block(), CricketErrorCode.MATCH_LOCKED);
        assertCode(() -> fx.useCase.submit(ORGANISER, fx.id(), UUID.randomUUID(), fx.seq, dot()).block(), CricketErrorCode.MATCH_LOCKED);
    }

    @Test
    void a_correction_left_open_at_the_deadline_is_locked_as_it_is() {
        var fx = new ScoringFixture();
        fx.scoreAll(GoldenMatch.wholeMatch());
        fx.clock.now = T0.plus(Duration.ofHours(23)).plus(Duration.ofMinutes(59));
        fx.undo(ScoringLog.UndoMode.BALL);
        fx.clock.now = T0.plus(Duration.ofHours(24)).plus(Duration.ofMinutes(1));

        assertCode(() -> fx.useCase.submit(ORGANISER, fx.id(), UUID.randomUUID(), fx.seq, dot()).block(), CricketErrorCode.MATCH_LOCKED);
        assertThat(fx.view().matchStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void a_match_that_has_never_finished_is_never_locked() {
        var fx = new ScoringFixture();
        fx.scoreAll(first(3));
        fx.clock.now = T0.plus(Duration.ofDays(90));

        assertThat(fx.view().locked()).isFalse();
        fx.score(dot());
    }

    // ---- the administrator's unlock ------------------------------------------------------------------------------

    private ScoringFixture lockedMatch() {
        var fx = new ScoringFixture();
        fx.scoreAll(GoldenMatch.wholeMatch());
        fx.clock.now = T0.plus(Duration.ofHours(25));
        return fx;
    }

    @Test
    void only_an_administrator_can_unlock_and_only_with_a_reason() {
        var fx = lockedMatch();

        assertCode(() -> fx.useCase.unlock(ORGANISER, fx.id(), UUID.randomUUID(), "scorer phone died during the final over").block(), CricketErrorCode.NOT_ALLOWED);
        assertCode(() -> fx.useCase.unlock(ADMIN, fx.id(), UUID.randomUUID(), "short").block(), CricketErrorCode.VALIDATION_ERROR);
        assertCode(() -> fx.useCase.unlock(ADMIN, fx.id(), UUID.randomUUID(), null).block(), CricketErrorCode.VALIDATION_ERROR);
        assertCode(() -> fx.useCase.unlock(ADMIN, UUID.randomUUID(), UUID.randomUUID(), "scorer phone died during the final over").block(), CricketErrorCode.MATCH_NOT_FOUND);
        assertThat(fx.view().locked()).isTrue();
    }

    @Test
    void an_unlock_is_audited_reopens_for_24_hours_and_locks_again_after_that() {
        var fx = lockedMatch();
        var id = UUID.randomUUID();

        var unlocked = fx.useCase.unlock(ADMIN, fx.id(), id, "  Scorer phone died during the final over.  ").block();
        fx.seq = unlocked.eventSeq();

        assertThat(unlocked.view().locked()).isFalse();
        assertThat(unlocked.view().editableUntil()).isEqualTo(T0.plus(Duration.ofHours(25)).plus(Duration.ofHours(24)));
        var entry = fx.store.entriesOf(fx.id()).get(fx.store.entriesOf(fx.id()).size() - 1);
        assertThat(entry.kind()).isEqualTo(LogEntry.Kind.ADMIN_UNLOCK);
        assertThat(entry.actorId()).isEqualTo(ADMIN.id());
        assertThat(entry.reason()).isEqualTo("Scorer phone died during the final over.");
        assertThat(entry.at()).isEqualTo(T0.plus(Duration.ofHours(25)));
        assertThat(unlocked.view().recentEvents().get(unlocked.view().recentEvents().size() - 1).text()).contains("Reopened by an administrator");

        assertThat(fx.undo(ScoringLog.UndoMode.BALL).view().matchStatus()).isEqualTo("IN_PROGRESS");
        var retry = fx.useCase.unlock(ADMIN, fx.id(), id, "Scorer phone died during the final over.").block();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.eventSeq()).isEqualTo(unlocked.eventSeq());

        fx.clock.now = T0.plus(Duration.ofHours(25)).plus(Duration.ofHours(24)).plus(Duration.ofMinutes(1));
        assertCode(() -> fx.useCase.submit(ORGANISER, fx.id(), UUID.randomUUID(), fx.seq, dot()).block(), CricketErrorCode.MATCH_LOCKED);
    }

    @Test
    void a_match_that_is_not_locked_cannot_be_unlocked() {
        var fx = new ScoringFixture();
        assertCode(() -> fx.useCase.unlock(ADMIN, fx.id(), UUID.randomUUID(), "scorer phone died during the final over").block(), CricketErrorCode.NOT_LOCKED);
        fx.scoreAll(GoldenMatch.wholeMatch());
        assertCode(() -> fx.useCase.unlock(ADMIN, fx.id(), UUID.randomUUID(), "scorer phone died during the final over").block(), CricketErrorCode.NOT_LOCKED);
    }

    // ---- the real-phone script of the runbook ------------------------------------------------------------------

    @Test
    void the_phone_script_of_the_runbook_gives_exactly_the_scorecard_and_the_log_it_promises() {
        var fx = new ScoringFixture(2, new MatchRules(6, 4, null, 1, 1, true, false), 4, 4, true);
        fx.score(new ScoreCommand.StartInnings(lion(1), lion(2), tiger(1)));
        fx.score(ball(Delivery.runs(1)));
        fx.score(ball(Delivery.boundary(4)));
        fx.score(dot());
        fx.score(ball(Delivery.dot().out(com.dsports.cricket.domain.rules.Dismissal.striker(com.dsports.cricket.domain.rules.Dismissal.Type.BOWLED))));
        fx.score(new ScoreCommand.Play(new ScoringCommand.SelectBatter(lion(3))));
        var mistake = fx.score(ball(Delivery.runs(3))).view();
        assertThat(mistake.canUndo().describe()).isEqualTo("Undo: 3 runs");
        assertThat(fx.undo(ScoringLog.UndoMode.BALL).view().innings().get(0).runs()).isEqualTo(5);
        fx.score(ball(Delivery.runs(2)));
        var overOne = fx.score(ball(Delivery.runs(1))).view();
        assertThat(overOne.needs()).isEqualTo("BOWLER");
        assertThat(overOne.bowlers().get(0).why()).isEqualTo("BOWLED_LAST_OVER");
        fx.score(new ScoreCommand.Play(new ScoringCommand.SelectBowler(tiger(2))));
        fx.score(ball(Delivery.runs(1)));
        fx.score(dot());
        fx.score(ball(Delivery.runs(1)));
        fx.score(ball(Delivery.boundary(6)));
        fx.score(ball(Delivery.runs(2)));
        var end = fx.score(dot()).view();

        var innings = end.innings().get(0);
        assertThat(end.matchStatus()).isEqualTo("INNINGS_BREAK");
        assertThat(innings.runs()).isEqualTo(18);
        assertThat(innings.wickets()).isEqualTo(1);
        assertThat(innings.overs()).isEqualTo("2.0");
        assertThat(innings.batters()).extracting(b -> b.player().name(), b -> b.runs(), b -> b.balls(), ScoringView.Batter::howOut)
            .containsExactly(org.assertj.core.api.Assertions.tuple("Lion 1", 2, 3, "not out"), org.assertj.core.api.Assertions.tuple("Lion 2", 4, 3, "b Tiger 1"),
                org.assertj.core.api.Assertions.tuple("Lion 3", 12, 6, "not out"));
        assertThat(innings.extras().total()).isZero();
        assertThat(innings.bowlers()).extracting(b -> b.player().name(), ScoringView.Bowler::overs, ScoringView.Bowler::maidens, ScoringView.Bowler::runs, ScoringView.Bowler::wickets)
            .containsExactly(org.assertj.core.api.Assertions.tuple("Tiger 1", "1.0", 0, 8, 1), org.assertj.core.api.Assertions.tuple("Tiger 2", "1.0", 0, 10, 0));
        var log = fx.store.entriesOf(fx.id());
        assertThat(log).hasSize(17);
        assertThat(log.stream().map(e -> e.kind() == LogEntry.Kind.COMMAND ? e.command().type().name() : e.kind().name()).toList()).containsExactly(
            "START_INNINGS", "DELIVER", "DELIVER", "DELIVER", "DELIVER", "SELECT_BATTER", "DELIVER", "UNDO", "DELIVER", "DELIVER", "SELECT_BOWLER",
            "DELIVER", "DELIVER", "DELIVER", "DELIVER", "DELIVER", "DELIVER");
        assertThat(log.get(7).voids()).containsExactly(7);
        assertThat(end.recentEvents().stream().map(ScoringView.Event::text)).anyMatch(t -> t.equals("Undid: 3 runs"));
    }

    // ---- waivers --------------------------------------------------------------------------------------------------

    @Test
    void a_last_resort_waiver_is_flagged_to_the_screen_stored_on_the_event_and_listed_on_the_scorecard() {
        var fx = new ScoringFixture(4, MatchRules.standard(), 11, 2, true);
        fx.score(new ScoreCommand.StartInnings(lion(1), lion(2), tiger(1)));
        for (int i = 0; i < 6; i++) {
            fx.score(dot());
        }
        fx.score(new ScoreCommand.Play(new ScoringCommand.SelectBowler(tiger(2))));
        for (int i = 0; i < 6; i++) {
            fx.score(dot());
        }
        assertThat(fx.view().needs()).isEqualTo("BOWLER");
        assertThat(fx.view().waiverRequired()).isTrue();

        var view = fx.score(new ScoreCommand.Play(new ScoringCommand.SelectBowler(tiger(1)))).view();

        assertThat(view.innings().get(0).waivers()).hasSize(1);
        var note = view.innings().get(0).waivers().get(0);
        assertThat(note.kind()).isEqualTo("OVER_LIMIT");
        assertThat(note.over()).isEqualTo(3);
        assertThat(note.bowler().name()).isEqualTo("Tiger 1");
        assertThat(note.text()).contains("Tiger 1").contains("over 3");
        var last = fx.store.entriesOf(fx.id()).get(fx.store.entriesOf(fx.id()).size() - 1);
        assertThat(last.waivers()).hasSize(1);
        assertThat(last.waivers().get(0).over()).isEqualTo(2);
    }
}
