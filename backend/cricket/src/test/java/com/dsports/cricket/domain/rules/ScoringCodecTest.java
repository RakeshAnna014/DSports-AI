package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.rules.InningsState.EndReason;
import com.dsports.cricket.domain.scoring.GoldenMatch;
import com.dsports.cricket.domain.scoring.MatchScore;
import com.dsports.cricket.domain.scoring.MatchScoring;
import com.dsports.cricket.domain.scoring.ScoreCommand;
import com.dsports.cricket.infrastructure.persistence.ScoringJsonCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What goes into the log and the snapshot as JSON comes back identical: the golden match and thousands of random states and commands. */
class ScoringCodecTest {

    private final ScoringJsonCodec codec = new ScoringJsonCodec();

    private ScoreCommand roundTrip(ScoreCommand command) {
        return codec.command(command.type(), codec.commandJson(command));
    }

    private MatchScore roundTrip(MatchScore score) {
        return codec.score(codec.scoreJson(score));
    }

    @Test
    void every_command_of_the_golden_match_survives_json() {
        for (var command : GoldenMatch.wholeMatch()) {
            assertThat(roundTrip(command)).as("%s", command).isEqualTo(command);
        }
    }

    @Test
    void every_command_kind_survives_json_including_the_ones_the_golden_match_does_not_use() {
        var commands = List.<ScoreCommand>of(
            new ScoreCommand.EndMatch(ScoreCommand.EndMatch.Reason.NO_RESULT),
            new ScoreCommand.Play(new ScoringCommand.ReviseTarget(41)),
            new ScoreCommand.Play(new ScoringCommand.EndInnings(EndReason.NO_BATTERS_LEFT)),
            new ScoreCommand.Play(new ScoringCommand.RetireBatter(new UUID(1, 2), ScoringCommand.RetirementType.OUT)),
            new ScoreCommand.Play(new ScoringCommand.ChangeBowlerMidOver(new UUID(3, 4))),
            new ScoreCommand.Play(new ScoringCommand.Deliver(Delivery.wide(2).out(Dismissal.striker(Dismissal.Type.STUMPED).by(new UUID(5, 6))))),
            new ScoreCommand.Play(new ScoringCommand.Deliver(Delivery.noBall(1, Delivery.RunsType.LEG_BYE))),
            new ScoreCommand.Play(new ScoringCommand.Deliver(Delivery.boundary(6))));
        for (var command : commands) {
            assertThat(roundTrip(command)).as("%s", command).isEqualTo(command);
        }
    }

    @Test
    void every_state_the_golden_match_passes_through_survives_json_and_replays_to_the_same_score() {
        var all = GoldenMatch.wholeMatch();
        for (int k = 0; k <= all.size(); k++) {
            var score = MatchScoring.fold(GoldenMatch.SETUP, all.subList(0, k));
            assertThat(roundTrip(score)).as("after %d commands", k).isEqualTo(score);
        }
    }

    @Test
    void a_match_that_was_ended_by_hand_survives_json() {
        var score = new MatchScore(List.of(), ScoreCommand.EndMatch.Reason.ABANDONED);
        assertThat(roundTrip(score)).isEqualTo(score);
    }

    @Test
    void waivers_and_voids_and_reasons_survive_json() {
        var waivers = List.of(new InningsState.Waiver(9, new UUID(1, 1), InningsState.WaiverKind.OVER_LIMIT), new InningsState.Waiver(2, new UUID(2, 2), InningsState.WaiverKind.CONSECUTIVE_OVER));
        assertThat(codec.waivers(codec.waiversJson(waivers))).isEqualTo(waivers);
        assertThat(codec.waivers("[]")).isEmpty();
        assertThat(codec.voids(codec.voidsJson(List.of(14, 13, 12)))).containsExactly(14, 13, 12);
        assertThat(codec.reason(codec.reasonJson("the scorer's phone died; \"fixed\" later"))).isEqualTo("the scorer's phone died; \"fixed\" later");
    }

    @Test
    void random_innings_states_and_commands_survive_json_and_the_decoded_log_replays_to_the_same_state() {
        int states = 0;
        for (long seed = 1; seed <= 150; seed++) {
            var random = new Random(seed);
            var game = RandomScorer.newGame(random);
            var decoded = new java.util.ArrayList<ScoringCommand>();
            for (var command : game.log) {
                decoded.add(command);
            }
            for (int step = 0; step < 300 && !game.s.isComplete(); step++) {
                var command = RandomScorer.nextCommand(random, game);
                if (ScoringEngine.apply(game.s, command) instanceof Outcome.Accepted accepted) {
                    var wrapped = new ScoreCommand.Play(command);
                    assertThat(roundTrip(wrapped)).as("seed %d", seed).isEqualTo(wrapped);
                    game.s = accepted.state();
                    decoded.add(command);
                    var score = new MatchScore(List.of(game.s), null);
                    assertThat(roundTrip(score)).as("seed %d state after %s", seed, command).isEqualTo(score);
                    states++;
                }
            }
        }
        assertThat(states).isGreaterThan(2_000);
    }
}
