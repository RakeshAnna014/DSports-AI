package com.dsports.cricket.application;

import com.dsports.cricket.application.result.CommandWire;
import com.dsports.cricket.application.result.EngineState;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.rules.Delivery;
import com.dsports.cricket.domain.rules.ScoringCommand;
import com.dsports.cricket.domain.scoring.GoldenMatch;
import com.dsports.cricket.domain.scoring.MatchScoring;
import com.dsports.cricket.domain.scoring.ScoreCommand;
import com.dsports.cricket.domain.scoring.ScoringLog;
import com.dsports.cricket.domain.scoring.StateFingerprint;
import com.dsports.cricket.interfaces.ScoringRequests;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static com.dsports.cricket.application.ScoringFixture.ADMIN;
import static com.dsports.cricket.application.ScoringFixture.OPPONENT;
import static com.dsports.cricket.application.ScoringFixture.ORGANISER;
import static com.dsports.cricket.application.ScoringFixture.SCORER;
import static com.dsports.cricket.application.ScoringFixture.STRANGER;
import static com.dsports.cricket.application.ScoringFixture.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the server gives a phone so that it can score offline (step 2f-3): the version of the rules and the fingerprint of the state on every view, the engine-state
 * route, which events are the asker's own, and the optional time the scorer says they tapped (for display only: nothing in the rules may ever read it).
 */
class OfflineScoringSupportTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private static ScoreCommand dot() {
        return new ScoreCommand.Play(new ScoringCommand.Deliver(Delivery.dot()));
    }

    // ---- the version and the fingerprint on the view ---------------------------------------------------------------------

    @Test
    void every_view_carries_the_engine_version_and_the_fingerprint_the_shared_vectors_record_for_the_same_step() throws Exception {
        var fx = new ScoringFixture();
        assertThat(fx.view().engineVersion()).isEqualTo(MatchScoring.ENGINE_VERSION);
        var vectors = JSON.readTree(Files.readString(Path.of(System.getProperty("basedir", ".")).resolve("../../testdata/cricket-engine/golden/golden-match.json").normalize()));
        var commands = GoldenMatch.wholeMatch();
        assertThat(vectors.get("steps")).hasSameSizeAs(commands);
        for (int i = 0; i < commands.size(); i++) {
            var view = fx.score(commands.get(i)).view();
            assertThat(view.stateFingerprint()).as("after step %d", i + 1).isEqualTo(vectors.get("steps").get(i).get("expect").get("fp").asText());
            assertThat(view.engineVersion()).isEqualTo(MatchScoring.ENGINE_VERSION);
        }
    }

    @Test
    void the_fingerprint_follows_an_undo_back_to_the_earlier_one() {
        var fx = new ScoringFixture();
        fx.score(ScoringFixture.lionsOpen());
        var before = fx.view().stateFingerprint();
        fx.score(dot());
        assertThat(fx.view().stateFingerprint()).isNotEqualTo(before);
        fx.undo(ScoringLog.UndoMode.COMMAND);
        assertThat(fx.view().stateFingerprint()).isEqualTo(before);
    }

    // ---- the engine-state route ------------------------------------------------------------------------------------------

    @Test
    void the_engine_state_holds_the_setup_the_whole_log_and_the_fingerprint_of_the_state_the_log_gives() {
        var fx = new ScoringFixture();
        fx.scoreAll(GoldenMatch.wholeMatch());
        var state = fx.useCase.engineState(ORGANISER, fx.id()).block();
        assertThat(state.matchId()).isEqualTo(fx.id());
        assertThat(state.engineVersion()).isEqualTo(MatchScoring.ENGINE_VERSION);
        assertThat(state.seq()).isEqualTo(GoldenMatch.wholeMatch().size());
        assertThat(state.ready()).isTrue();
        assertThat(state.stateFingerprint()).isEqualTo(fx.view().stateFingerprint());
        assertThat(state.setup()).extracting("overs", "teamA", "teamB", "battingFirst").containsExactly(2, GoldenMatch.LIONS, GoldenMatch.TIGERS, GoldenMatch.LIONS);
        assertThat(state.setup().xiA()).hasSize(11).first().isEqualTo(GoldenMatch.lion(1));
        assertThat(state.entries()).extracting(EngineState.Entry::seq).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, state.seq()).boxed().toList());
        assertThat(state.entries()).allSatisfy(e -> assertThat(e.kind()).isEqualTo("COMMAND"));
    }

    @Test
    void the_engine_state_has_undos_with_what_they_voided_and_commands_that_read_back_through_the_request_class() throws Exception {
        var fx = new ScoringFixture();
        fx.score(ScoringFixture.lionsOpen());
        fx.score(dot());
        fx.score(new ScoreCommand.Play(new ScoringCommand.Deliver(Delivery.wide(0).out(com.dsports.cricket.domain.rules.Dismissal.of(com.dsports.cricket.domain.rules.Dismissal.Type.STUMPED, null).by(GoldenMatch.tiger(2))))));
        fx.undo(ScoringLog.UndoMode.COMMAND);
        var state = fx.useCase.engineState(ORGANISER, fx.id()).block();
        assertThat(state.entries()).extracting(EngineState.Entry::kind).containsExactly("COMMAND", "COMMAND", "COMMAND", "UNDO");
        assertThat(state.entries().get(3).voids()).containsExactly(3);
        assertThat(state.entries().get(3).command()).isNull();
        // The command bodies are the request bodies: read back through the class the server reads a request with, they are the same commands.
        var entries = fx.store.entriesOf(fx.id());
        for (int i = 0; i < 3; i++) {
            var back = JSON.convertValue(state.entries().get(i).command(), ScoringRequests.CommandBody.class).toCommand();
            assertThat(back).isEqualTo(entries.get(i).command());
            assertThat(CommandWire.body(back)).isEqualTo(state.entries().get(i).command());
        }
        assertThat(JSON.writeValueAsString(state)).doesNotContain("@").doesNotContain("Olive").doesNotContain("Sam ");
    }

    @Test
    void only_the_askers_own_events_carry_a_client_event_id_and_are_marked_mine() {
        var fx = new ScoringFixture();
        var own = UUID.randomUUID();
        var theirs = UUID.randomUUID();
        fx.useCase.submit(ORGANISER, fx.id(), own, 0, ScoringFixture.lionsOpen()).block();
        fx.useCase.submit(SCORER, fx.id(), theirs, 1, dot()).block();
        var asOrganiser = fx.useCase.engineState(ORGANISER, fx.id()).block();
        assertThat(asOrganiser.entries()).extracting(EngineState.Entry::mine, EngineState.Entry::clientEventId).containsExactly(org.assertj.core.api.Assertions.tuple(true, own),
            org.assertj.core.api.Assertions.tuple(false, null));
        var asScorer = fx.useCase.engineState(SCORER, fx.id()).block();
        assertThat(asScorer.entries()).extracting(EngineState.Entry::mine, EngineState.Entry::clientEventId).containsExactly(org.assertj.core.api.Assertions.tuple(false, null),
            org.assertj.core.api.Assertions.tuple(true, theirs));
        var events = fx.view().recentEvents();
        assertThat(events).extracting(e -> e.mine(), e -> e.clientEventId()).containsExactly(org.assertj.core.api.Assertions.tuple(true, own), org.assertj.core.api.Assertions.tuple(false, null));
        assertThat(fx.useCase.state(SCORER, fx.id()).block().recentEvents()).extracting(e -> e.mine(), e -> e.clientEventId())
            .containsExactly(org.assertj.core.api.Assertions.tuple(false, null), org.assertj.core.api.Assertions.tuple(true, theirs));
    }

    @Test
    void a_conflict_answer_marks_the_askers_own_events_too_so_a_phone_can_tell_what_the_server_already_has() {
        var fx = new ScoringFixture();
        fx.score(ScoringFixture.lionsOpen());
        var mine = UUID.randomUUID();
        fx.useCase.submit(ORGANISER, fx.id(), mine, 1, dot()).block();
        fx.useCase.submit(SCORER, fx.id(), UUID.randomUUID(), 2, dot()).block();
        var failure = org.assertj.core.api.Assertions.catchThrowableOfType(CricketDomainException.class, () -> fx.useCase.submit(ORGANISER, fx.id(), UUID.randomUUID(), 1, dot()).block());
        assertThat(failure.getErrorCode()).isEqualTo(CricketErrorCode.SCORE_CONFLICT);
        var conflict = (com.dsports.cricket.application.result.ScoringView.Conflict) failure.getDetails();
        assertThat(conflict.eventsSince()).extracting(e -> e.mine(), e -> e.clientEventId()).containsExactly(org.assertj.core.api.Assertions.tuple(true, mine), org.assertj.core.api.Assertions.tuple(false, null));
        assertThat(conflict.view().stateFingerprint()).isEqualTo(fx.view().stateFingerprint());
    }

    @Test
    void the_engine_state_is_for_the_same_people_as_the_view_and_says_when_the_match_is_not_ready() {
        var fx = new ScoringFixture();
        assertThatThrownBy(() -> fx.useCase.engineState(STRANGER, fx.id()).block()).isInstanceOfSatisfying(CricketDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(CricketErrorCode.MATCH_NOT_FOUND));
        assertThatThrownBy(() -> fx.useCase.engineState(OPPONENT, fx.id()).block()).isInstanceOfSatisfying(CricketDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(CricketErrorCode.NOT_ALLOWED));
        assertThatThrownBy(() -> fx.useCase.engineState(ADMIN, fx.id()).block()).as("an administrator who is not a scorer sees nothing here, as with the view").isInstanceOf(CricketDomainException.class);
        assertThat(fx.useCase.engineState(SCORER, fx.id()).block().entries()).isEmpty();
        var unready = new ScoringFixture(2, com.dsports.cricket.domain.model.MatchRules.standard(), 11, 11, false);
        var state = unready.useCase.engineState(ORGANISER, unready.id()).block();
        assertThat(state.ready()).isFalse();
        assertThat(state.entries()).isEmpty();
        assertThat(state.seq()).isZero();
        assertThat(state.stateFingerprint()).isEqualTo(StateFingerprint.of(com.dsports.cricket.domain.scoring.MatchScore.empty()));
    }

    // ---- tappedAt: for display only ---------------------------------------------------------------------------------------

    @Test
    void the_time_a_ball_was_tapped_is_kept_for_the_timeline_and_never_later_than_when_the_server_got_it() {
        var fx = new ScoringFixture();
        fx.score(ScoringFixture.lionsOpen());
        fx.useCase.submit(ORGANISER, fx.id(), UUID.randomUUID(), 1, dot(), T0.minus(Duration.ofMinutes(7))).block();
        fx.useCase.submit(ORGANISER, fx.id(), UUID.randomUUID(), 2, dot(), T0.plus(Duration.ofDays(3))).block();
        fx.useCase.submit(ORGANISER, fx.id(), UUID.randomUUID(), 3, dot(), null).block();
        var events = fx.view().recentEvents();
        assertThat(events).extracting(e -> e.tappedAt()).containsExactly(null, T0.minus(Duration.ofMinutes(7)), T0, null);
        assertThat(events).extracting(e -> e.at()).as("the server's own time is untouched").containsOnly(T0);
    }

    @Test
    void a_tapped_time_that_cannot_be_believed_is_not_kept_and_never_breaks_the_request() {
        var fx = new ScoringFixture();
        fx.score(ScoringFixture.lionsOpen());
        fx.useCase.submit(ORGANISER, fx.id(), UUID.randomUUID(), 1, dot(), Instant.MIN).block();
        fx.useCase.submit(ORGANISER, fx.id(), UUID.randomUUID(), 2, dot(), T0.minus(Duration.ofDays(31))).block();
        fx.useCase.submit(ORGANISER, fx.id(), UUID.randomUUID(), 3, dot(), T0.minus(Duration.ofDays(29))).block();
        fx.useCase.submit(ORGANISER, fx.id(), UUID.randomUUID(), 4, dot(), Instant.MAX).block();
        assertThat(fx.view().recentEvents()).extracting(e -> e.tappedAt()).containsExactly(null, null, null, T0.minus(Duration.ofDays(29)), T0);
    }

    @Test
    void the_tapped_time_is_kept_on_an_undo_too() {
        var fx = new ScoringFixture();
        fx.score(ScoringFixture.lionsOpen());
        fx.useCase.undo(ORGANISER, fx.id(), UUID.randomUUID(), 1, ScoringLog.UndoMode.COMMAND, T0.minusSeconds(5)).block();
        assertThat(fx.view().recentEvents().get(1).tappedAt()).isEqualTo(T0.minusSeconds(5));
    }

    @Test
    void no_rule_reads_it_the_same_balls_with_any_tapped_times_give_the_same_state_seqs_window_and_lock() {
        var plain = new ScoringFixture();
        var odd = new ScoringFixture();
        var commands = GoldenMatch.wholeMatch();
        var weird = new Instant[] {null, T0.minus(Duration.ofDays(400)), T0.plus(Duration.ofDays(400)), T0, Instant.EPOCH, T0.minusNanos(1)};
        for (int i = 0; i < commands.size(); i++) {
            plain.score(commands.get(i));
            var outcome = odd.useCase.submit(ORGANISER, odd.id(), UUID.randomUUID(), i, commands.get(i), weird[i % weird.length]).block();
            odd.seq = outcome.eventSeq();
            assertThat(outcome.view().stateFingerprint()).as("step %d", i + 1).isEqualTo(plain.view().stateFingerprint());
            assertThat(outcome.eventSeq()).isEqualTo(i + 1);
            assertThat(outcome.view().editableUntil()).isEqualTo(plain.view().editableUntil());
        }
        // The 24 hour window is the server's: a finish received at T0 locks at T0 + 24 hours whatever the phone said, and an administrator's unlock is by the server's clock.
        odd.clock.now = T0.plus(Duration.ofHours(24)).plusSeconds(1);
        plain.clock.now = odd.clock.now;
        assertThat(odd.view().locked()).isTrue().isEqualTo(plain.view().locked());
        assertThat(odd.view().editableUntil()).isEqualTo(T0.plus(Duration.ofHours(24)));
    }

    @Test
    void it_is_not_part_of_the_request_fingerprint_so_a_retry_with_another_tapped_time_is_still_a_replay_not_a_reused_id() {
        var fx = new ScoringFixture();
        fx.score(ScoringFixture.lionsOpen());
        var id = UUID.randomUUID();
        var first = fx.useCase.submit(ORGANISER, fx.id(), id, 1, dot(), T0.minusSeconds(60)).block();
        var retry = fx.useCase.submit(ORGANISER, fx.id(), id, 1, dot(), T0.minusSeconds(5)).block();
        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.eventSeq()).isEqualTo(first.eventSeq());
        assertThat(fx.store.entriesOf(fx.id())).hasSize(2);
        assertThat(fx.store.entriesOf(fx.id()).get(1).tappedAt()).as("the first tapped time is the one kept").isEqualTo(T0.minusSeconds(60));
    }

    @Test
    void a_rebased_ball_sent_again_with_a_new_expected_seq_but_the_same_id_is_recognised_before_the_seq_is_checked() {
        var fx = new ScoringFixture();
        fx.score(ScoringFixture.lionsOpen());
        var id = UUID.randomUUID();
        fx.useCase.submit(ORGANISER, fx.id(), id, 1, dot()).block();
        fx.useCase.submit(SCORER, fx.id(), UUID.randomUUID(), 2, dot()).block();
        var again = fx.useCase.submit(ORGANISER, fx.id(), id, 1, dot()).block();
        assertThat(again.replayed()).isTrue();
        assertThat(again.eventSeq()).isEqualTo(2);
        assertThat(fx.store.entriesOf(fx.id())).hasSize(3);
    }
}
