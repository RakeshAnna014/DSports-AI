package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.rules.Delivery.Kind;
import com.dsports.cricket.domain.rules.Delivery.RunsType;
import com.dsports.cricket.domain.rules.InningsState.BatterLine;
import com.dsports.cricket.domain.rules.InningsState.BatterStatus;
import com.dsports.cricket.domain.rules.InningsState.EndReason;
import com.dsports.cricket.domain.rules.InningsState.Status;
import com.dsports.cricket.domain.rules.ScoringCommand.ChangeBowlerMidOver;
import com.dsports.cricket.domain.rules.ScoringCommand.Deliver;
import com.dsports.cricket.domain.rules.ScoringCommand.EndInnings;
import com.dsports.cricket.domain.rules.ScoringCommand.RetireBatter;
import com.dsports.cricket.domain.rules.ScoringCommand.RetirementType;
import com.dsports.cricket.domain.rules.ScoringCommand.ReviseTarget;
import com.dsports.cricket.domain.rules.ScoringCommand.SelectBatter;
import com.dsports.cricket.domain.rules.ScoringCommand.SelectBowler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property tests: thousands of random commands (many of them illegal) are fed to the engine from many seeds, and after every accepted one the invariants of
 * a scorecard must hold. Seeded, so a failure names the seed and reproduces.
 */
class EngineInvariantsTest {

    private static final int SEEDS = 500;
    private static final int STEPS = 700;

    @Test
    void every_accepted_command_leaves_a_consistent_innings_and_every_rejection_is_repeatable() {
        var endings = new EnumMap<EndReason, Integer>(EndReason.class);
        int accepted = 0;
        int rejected = 0;
        int wickets = 0;
        int retirements = 0;
        int midOverChanges = 0;
        for (long seed = 1; seed <= SEEDS; seed++) {
            var random = new Random(seed);
            var game = newGame(random);
            var history = new ArrayList<ScoringCommand>(game.log);
            for (int step = 0; step < STEPS && !game.s.isComplete(); step++) {
                var command = nextCommand(random, game);
                var before = game.s;
                var outcome = ScoringEngine.apply(before, command);
                assertThat(ScoringEngine.apply(before, command)).as("seed %d: the same command on the same state gives the same answer", seed).isEqualTo(outcome);
                if (outcome instanceof Outcome.Accepted a) {
                    accepted++;
                    game.s = a.state();
                    history.add(command);
                    check(a.state(), seed);
                    wickets += a.state().wickets() - before.wickets();
                    if (command instanceof RetireBatter) {
                        retirements++;
                    }
                    if (command instanceof ChangeBowlerMidOver) {
                        midOverChanges++;
                    }
                } else {
                    rejected++;
                }
            }
            if (game.s.isComplete()) {
                endings.merge(game.s.endReason(), 1, Integer::sum);
            }
            assertThat(ScoringEngine.replay(history)).as("seed %d: replaying the accepted commands rebuilds the innings", seed).isEqualTo(game.s);
        }

        var summary = "accepted " + accepted + ", rejected " + rejected + ", wickets " + wickets + ", retirements " + retirements + ", bowler changes " + midOverChanges + ", endings " + endings;
        assertThat(accepted).as(summary).isGreaterThan(8_000);
        assertThat(rejected).as(summary).isGreaterThan(3_000);
        assertThat(wickets).as(summary).isGreaterThan(400);
        assertThat(retirements).as(summary).isGreaterThan(100);
        assertThat(midOverChanges).as(summary).isGreaterThan(100);
        assertThat(endings.keySet()).as(summary).contains(EndReason.ALL_OUT, EndReason.OVERS_COMPLETE, EndReason.TARGET_REACHED, EndReason.DECLARED);
    }

    // ---- the invariants ---------------------------------------------------------------------------------------------

    private static void check(InningsState s, long seed) {
        var why = "seed " + seed;
        int bpo = s.rules().ballsPerOver();
        int batRuns = s.batters().stream().mapToInt(BatterLine::runs).sum();
        assertThat(s.runs()).as("%s: total = bat runs + extras", why).isEqualTo(batRuns + s.extras().total());
        assertThat(s.bowlers().stream().mapToInt(b -> b.runs()).sum() + s.extras().byes() + s.extras().legByes()).as("%s: bowlers + byes + leg byes = total", why).isEqualTo(s.runs());
        assertThat(s.bowlers().stream().mapToInt(b -> b.legalBalls()).sum()).as("%s: legal balls add up", why).isEqualTo(s.legalBalls());
        assertThat(s.bowlers().stream().mapToInt(b -> b.creditedOvers()).sum()).as("%s: every finished over is credited once", why).isEqualTo(s.legalBalls() / bpo);
        assertThat(s.legalBalls()).isLessThanOrEqualTo(s.overs() * bpo);
        assertThat(s.over().legalBalls()).as("%s: legal balls in the over", why).isEqualTo(s.legalBalls() % bpo);
        assertThat(s.over().shares().stream().mapToInt(x -> x.legalBalls()).sum()).isEqualTo(s.over().legalBalls());
        assertThat(s.over().deliveries()).isGreaterThanOrEqualTo(s.over().legalBalls());

        long out = s.batters().stream().filter(b -> b.status() == BatterStatus.OUT || b.status() == BatterStatus.RETIRED_OUT).count();
        assertThat((long) s.wickets()).as("%s: wickets = batters out", why).isEqualTo(out).isEqualTo(s.fallOfWickets().size());
        for (int i = 0; i < s.fallOfWickets().size(); i++) {
            assertThat(s.fallOfWickets().get(i).number()).isEqualTo(i + 1);
            assertThat(s.fallOfWickets().get(i).runs()).isLessThanOrEqualTo(s.runs());
        }
        assertThat(s.wickets()).isLessThanOrEqualTo(s.rules().lastManStands() ? s.battingXi().size() : s.battingXi().size() - 1);

        var lines = new HashSet<UUID>();
        for (var b : s.batters()) {
            assertThat(lines.add(b.playerId())).as("%s: one line per batter", why).isTrue();
            assertThat(s.battingXi()).contains(b.playerId());
        }
        assertThat(s.yetToBat()).doesNotContainAnyElementsOf(lines);
        for (var end : new UUID[]{s.atBattingEnd(), s.atBowlersEnd()}) {
            if (end != null) {
                assertThat(s.batter(end).orElseThrow().status()).as("%s: a batter at the crease is not out", why).isEqualTo(BatterStatus.NOT_OUT);
            }
        }
        if (s.atBattingEnd() != null && s.atBowlersEnd() != null) {
            assertThat(s.atBattingEnd()).isNotEqualTo(s.atBowlersEnd());
        }
        assertThat(s.bowlingXi()).contains(s.currentBowler());
        s.bowlers().forEach(b -> assertThat(s.bowlingXi()).contains(b.playerId()));
        if (!s.rules().freeHitEnabled()) {
            assertThat(s.freeHit()).isFalse();
        }

        if (s.status() == Status.IN_PROGRESS) {
            assertThat(s.legalBalls()).isLessThan(s.overs() * bpo);
            if (s.target() != null) {
                assertThat(s.runs()).as("%s: a chase still going has not reached its target", why).isLessThan(s.target());
            }
            if (s.needsBatter()) {
                assertThat(s.atBattingEnd() == null || s.atBowlersEnd() == null).as("%s: a batter is needed, so an end is empty", why).isTrue();
            } else {
                assertThat(s.atBattingEnd()).as("%s: someone is on strike", why).isNotNull();
                if (!s.rules().lastManStands()) {
                    assertThat(s.atBowlersEnd()).as("%s: two batters without last man stands", why).isNotNull();
                }
            }
        } else {
            assertThat(s.needsBatter() || s.needsBowler()).as("%s: nothing is needed once the innings is over", why).isFalse();
            assertThat(s.endReason()).isNotNull();
        }
        assertThat(Scorecard.of(s).runs()).isEqualTo(s.runs());
    }

    // ---- the random scorer -------------------------------------------------------------------------------------------

    private static Game newGame(Random r) {
        int players = 2 + r.nextInt(10);
        int overs = 1 + r.nextInt(5);
        var rules = new MatchRules(4 + r.nextInt(5), players, r.nextInt(3) == 0 ? 1 : null, r.nextInt(3), r.nextInt(3), r.nextInt(5) != 0, r.nextInt(4) == 0);
        Integer target = r.nextInt(3) == 0 ? 1 + r.nextInt(70) : null;
        return new Game(rules, overs, players, Math.min(players, 2 + r.nextInt(4)), target);
    }

    private static ScoringCommand nextCommand(Random r, Game g) {
        var s = g.s;
        if (s.needsBatter() && r.nextInt(10) < 8) {
            return new SelectBatter(pick(r, s.battingXi()));
        }
        if (s.needsBowler() && r.nextInt(10) < 8) {
            return new SelectBowler(pick(r, s.bowlingXi()));
        }
        int p = r.nextInt(1000);
        if (p < 800) {
            return new Deliver(randomDelivery(r, s));
        }
        if (p < 850) {
            return new RetireBatter(r.nextInt(5) == 0 ? pick(r, s.battingXi()) : crease(r, s), r.nextInt(8) == 0 ? null : RetirementType.values()[r.nextInt(2)]);
        }
        if (p < 920) {
            return new ChangeBowlerMidOver(pick(r, s.bowlingXi()));
        }
        if (p < 925) {
            return new EndInnings(EndReason.values()[r.nextInt(EndReason.values().length)]);
        }
        if (p < 940) {
            return new ReviseTarget(r.nextInt(80));
        }
        return r.nextBoolean() ? new SelectBatter(pick(r, s.battingXi())) : new SelectBowler(pick(r, s.bowlingXi()));
    }

    private static Delivery randomDelivery(Random r, InningsState s) {
        int k = r.nextInt(100);
        var kind = k < 76 ? Kind.NORMAL : k < 88 ? Kind.WIDE : Kind.NO_BALL;
        int runs = r.nextInt(100) < 60 ? r.nextInt(3) : r.nextInt(9);
        var type = RunsType.values()[r.nextInt(RunsType.values().length)];
        if (r.nextInt(100) < 60) {
            type = RunsType.BAT;
        }
        boolean boundary = r.nextInt(10) == 0;
        if (boundary) {
            runs = r.nextBoolean() ? 4 : 6;
        }
        Dismissal dismissal = null;
        if (r.nextInt(100) < 12) {
            var type2 = Dismissal.Type.values()[r.nextInt(Dismissal.Type.values().length)];
            UUID batter = r.nextInt(3) == 0 ? null : (r.nextInt(8) == 0 ? pick(r, s.battingXi()) : crease(r, s));
            var end = r.nextInt(6) == 0 ? null : Dismissal.End.values()[r.nextInt(2)];
            dismissal = new Dismissal(type2, batter, end);
        }
        return new Delivery(kind, runs, type, boundary, dismissal);
    }

    private static UUID crease(Random r, InningsState s) {
        var at = new ArrayList<UUID>();
        if (s.atBattingEnd() != null) {
            at.add(s.atBattingEnd());
        }
        if (s.atBowlersEnd() != null) {
            at.add(s.atBowlersEnd());
        }
        return at.isEmpty() ? pick(r, s.battingXi()) : pick(r, at);
    }

    private static UUID pick(Random r, List<UUID> from) {
        return from.get(r.nextInt(from.size()));
    }

    @SuppressWarnings("unused")
    private static Map<String, Integer> unused() {
        return Map.of();
    }
}
