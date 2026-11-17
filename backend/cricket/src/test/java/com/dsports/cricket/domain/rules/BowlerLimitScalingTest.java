package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.rules.InningsState.EndReason;
import com.dsports.cricket.domain.rules.InningsState.Waiver;
import com.dsports.cricket.domain.rules.InningsState.WaiverKind;
import com.dsports.cricket.domain.rules.ScoringCommand.SelectBowler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The default bowler limit follows the size of the side, and setting it aside as a last resort is recorded, never silent. */
class BowlerLimitScalingTest {

    private static MatchRules sides(int players) {
        return Game.withPlayers(players);
    }

    // ---- the default limit -------------------------------------------------------------------------------------

    @Test
    void an_eleven_a_side_match_keeps_a_fifth_of_the_match() {
        var rules = sides(11);
        assertThat(rules.bowlerLimit(1)).isEqualTo(1);
        assertThat(rules.bowlerLimit(4)).isEqualTo(1);
        assertThat(rules.bowlerLimit(10)).isEqualTo(2);
        assertThat(rules.bowlerLimit(20)).isEqualTo(4);
        assertThat(rules.bowlerLimit(50)).isEqualTo(10);
    }

    @Test
    void a_six_a_side_match_shares_the_overs_between_five_bowlers_which_is_still_a_fifth() {
        var rules = sides(6);
        assertThat(rules.bowlerLimit(10)).isEqualTo(2);
        assertThat(rules.bowlerLimit(12)).isEqualTo(3);
        assertThat(rules.bowlerLimit(20)).isEqualTo(4);
    }

    @Test
    void a_smaller_side_gets_a_bigger_limit_so_it_can_still_bowl_its_overs() {
        assertThat(sides(4).bowlerLimit(20)).isEqualTo(7);
        assertThat(sides(3).bowlerLimit(12)).isEqualTo(6);
        assertThat(sides(2).bowlerLimit(5)).isEqualTo(5);
        assertThat(sides(5).bowlerLimit(10)).isEqualTo(3);
    }

    @Test
    void a_limit_set_for_the_match_always_wins() {
        assertThat(Game.rules(6, 11, 3, 1, 1, true, false).bowlerLimit(20)).isEqualTo(3);
        assertThat(Game.rules(6, 3, 2, 1, 1, true, false).bowlerLimit(12)).isEqualTo(2);
    }

    // ---- playing whole matches ---------------------------------------------------------------------------------

    private static Game rotate(Game g, int bowlers, int overs) {
        for (int over = 1; over <= overs; over++) {
            g.dots(g.s.rules().ballsPerOver());
            if (over < overs) {
                g.bowler(over % bowlers + 1);
            }
        }
        return g;
    }

    @Test
    void an_eleven_a_side_twenty_over_innings_needs_no_waiver_with_five_bowlers() {
        var g = rotate(new Game(sides(11), 20, 11, 5, null), 5, 20);

        assertThat(g.s.endReason()).isEqualTo(EndReason.OVERS_COMPLETE);
        assertThat(g.s.waivers()).isEmpty();
        assertThat(g.s.bowlers()).allSatisfy(b -> assertThat(b.creditedOvers()).isEqualTo(4));
        assertThat(Scorecard.of(g.s).waivers()).isEmpty();
    }

    @Test
    void a_six_a_side_twelve_over_innings_needs_no_waiver_with_four_bowlers() {
        var g = rotate(new Game(sides(6), 12, 6, 4, null), 4, 12);

        assertThat(g.s.endReason()).isEqualTo(EndReason.OVERS_COMPLETE);
        assertThat(g.s.waivers()).isEmpty();
        assertThat(g.s.bowlers()).allSatisfy(b -> assertThat(b.creditedOvers()).isEqualTo(3));
    }

    @Test
    void a_three_a_side_innings_can_be_completed_by_its_three_bowlers_without_a_waiver() {
        var g = rotate(new Game(sides(3), 12, 3, 3, null), 3, 12);

        assertThat(g.s.endReason()).isEqualTo(EndReason.OVERS_COMPLETE);
        assertThat(g.s.waivers()).isEmpty();
        assertThat(g.s.bowlers()).allSatisfy(b -> assertThat(b.creditedOvers()).isEqualTo(4));
    }

    // ---- the waiver, as a last resort --------------------------------------------------------------------------

    @Test
    void when_everyone_is_at_the_limit_the_waiver_is_recorded_on_the_event_and_the_innings_and_shown_on_the_scorecard() {
        var g = new Game(sides(6), 12, 6, 3, null);
        rotate(g, 3, 9);
        assertThat(g.s.waivers()).isEmpty();
        assertThat(g.s.needsBowler()).isTrue();

        var outcome = ScoringEngine.apply(g.s, new SelectBowler(g.f(1)));

        var accepted = (Outcome.Accepted) outcome;
        assertThat(accepted.waivers()).containsExactly(new Waiver(9, g.f(1), WaiverKind.OVER_LIMIT));
        g.bowler(1);
        assertThat(g.s.waivers()).containsExactly(new Waiver(9, g.f(1), WaiverKind.OVER_LIMIT));
        assertThat(Scorecard.of(g.s).waivers()).containsExactly(new Waiver(9, g.f(1), WaiverKind.OVER_LIMIT));
        assertThat(g.s.bowler(g.f(1)).orElseThrow().creditedOvers()).isEqualTo(3);
    }

    @Test
    void an_ordinary_choice_records_nothing() {
        var g = Game.standard(20).dots(6);
        var accepted = (Outcome.Accepted) ScoringEngine.apply(g.s, new SelectBowler(g.f(2)));

        assertThat(accepted.waivers()).isEmpty();
    }

    @Test
    void the_waiver_for_a_mid_over_change_names_the_over_it_happened_in() {
        var g = new Game(sides(11), 4, 11, 2, null);
        g.dotOverThen(2).dotOverThen(1);
        g.dots(1);
        var accepted = (Outcome.Accepted) ScoringEngine.apply(g.s, new ScoringCommand.ChangeBowlerMidOver(g.f(2)));

        assertThat(accepted.waivers()).containsExactly(new Waiver(2, g.f(2), WaiverKind.CONSECUTIVE_OVER));
        assertThat(accepted.state().waivers()).extracting(Waiver::kind).containsExactly(WaiverKind.OVER_LIMIT, WaiverKind.CONSECUTIVE_OVER);
    }

    @Test
    void a_bowler_who_would_have_to_bowl_twice_in_a_row_is_the_last_resort_and_is_recorded_too() {
        var g = new Game(sides(11), 6, 11, 2, null);
        g.dotOverThen(2).dots(1);
        var accepted = (Outcome.Accepted) ScoringEngine.apply(g.s, new ScoringCommand.ChangeBowlerMidOver(g.f(1)));

        assertThat(accepted.waivers()).containsExactly(new Waiver(1, g.f(1), WaiverKind.CONSECUTIVE_OVER));
    }

    @Test
    void waivers_are_part_of_the_replayed_innings() {
        var g = new Game(sides(6), 12, 6, 3, null);
        rotate(g, 3, 9);
        g.bowler(1);

        assertThat(ScoringEngine.replay(g.log)).isEqualTo(g.s);
        assertThat(ScoringEngine.replay(g.log).waivers()).hasSize(1);
    }
}
