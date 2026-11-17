package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.rules.Delivery.Kind;
import com.dsports.cricket.domain.rules.Delivery.RunsType;
import com.dsports.cricket.domain.rules.Dismissal.End;
import com.dsports.cricket.domain.rules.InningsState.BatterLine;
import com.dsports.cricket.domain.rules.InningsState.BatterStatus;
import com.dsports.cricket.domain.rules.InningsState.BowlerLine;
import com.dsports.cricket.domain.rules.InningsState.EndReason;
import com.dsports.cricket.domain.rules.InningsState.Extras;
import com.dsports.cricket.domain.rules.InningsState.FallOfWicket;
import com.dsports.cricket.domain.rules.InningsState.Over;
import com.dsports.cricket.domain.rules.InningsState.OverShare;
import com.dsports.cricket.domain.rules.InningsState.Status;
import com.dsports.cricket.domain.rules.InningsState.Waiver;
import com.dsports.cricket.domain.rules.InningsState.WaiverKind;
import com.dsports.cricket.domain.rules.ScoringCommand.Deliver;
import com.dsports.cricket.domain.rules.ScoringCommand.EndInnings;
import com.dsports.cricket.domain.rules.ScoringCommand.ChangeBowlerMidOver;
import com.dsports.cricket.domain.rules.ScoringCommand.RetireBatter;
import com.dsports.cricket.domain.rules.ScoringCommand.ReviseTarget;
import com.dsports.cricket.domain.rules.ScoringCommand.SelectBatter;
import com.dsports.cricket.domain.rules.ScoringCommand.SelectBowler;
import com.dsports.cricket.domain.rules.ScoringCommand.StartInnings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The scoring rules of one innings as a pure function: {@code apply(state, command)} returns the new state or the rule that was broken.
 * No clock, no randomness, no framework. A rejected command changes nothing.
 */
public final class ScoringEngine {

    /** Most runs of one kind (bat, bye, leg bye, wide) on one ball. The penalty of a wide or no-ball is added on top. */
    public static final int MAX_RUNS = 7;

    private ScoringEngine() {
    }

    public static Outcome apply(InningsState state, ScoringCommand command) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(command, "command");
        return switch (command) {
            case StartInnings c -> start(state, c);
            case Deliver c -> deliver(state, c);
            case SelectBatter c -> selectBatter(state, c);
            case SelectBowler c -> selectBowler(state, c);
            case ChangeBowlerMidOver c -> changeBowlerMidOver(state, c);
            case RetireBatter c -> retire(state, c);
            case ReviseTarget c -> reviseTarget(state, c);
            case EndInnings c -> endInnings(state, c);
        };
    }

    /** Applies the commands in order from an empty innings. Used to rebuild a state from a log; every command in it must have been accepted before. */
    public static InningsState replay(List<? extends ScoringCommand> commands) {
        var state = InningsState.notStarted();
        for (var command : commands) {
            if (apply(state, command) instanceof Outcome.Accepted accepted) {
                state = accepted.state();
            } else {
                throw new IllegalStateException("A logged command is no longer accepted: " + command);
            }
        }
        return state;
    }

    // ---- start ---------------------------------------------------------------------------------------------------

    private static Outcome start(InningsState s, StartInnings c) {
        if (s.status() != Status.NOT_STARTED) {
            return reject(RuleViolation.INNINGS_ALREADY_STARTED, "This innings has already started");
        }
        if (c.rules() == null || c.overs() < 1 || c.overs() > 100 || c.battingTeamId() == null || c.bowlingTeamId() == null || c.battingTeamId().equals(c.bowlingTeamId())) {
            return reject(RuleViolation.INVALID_SETUP, "An innings needs rules, 1 to 100 overs and two different teams");
        }
        try {
            c.rules().validate(c.overs());
        } catch (CricketDomainException e) {
            return reject(RuleViolation.INVALID_SETUP, e.getMessage());
        }
        if (!validXi(c.battingXi(), c.rules()) || !validXi(c.bowlingXi(), c.rules()) || c.battingXi().stream().anyMatch(c.bowlingXi()::contains)) {
            return reject(RuleViolation.INVALID_SETUP, "Each side needs 2 to " + c.rules().playersPerSide() + " different players, and nobody plays for both sides");
        }
        if (c.striker() == null || c.nonStriker() == null || c.striker().equals(c.nonStriker()) || !c.battingXi().contains(c.striker()) || !c.battingXi().contains(c.nonStriker())) {
            return reject(RuleViolation.INVALID_OPENERS, "The openers must be two different players from the batting XI");
        }
        if (c.bowler() == null || !c.bowlingXi().contains(c.bowler())) {
            return reject(RuleViolation.BOWLER_NOT_IN_XI, "The opening bowler must be in the bowling XI");
        }
        if (c.target() != null && c.target() < 1) {
            return reject(RuleViolation.INVALID_TARGET, "The target must be at least 1");
        }
        return new Outcome.Accepted(new InningsState(Status.IN_PROGRESS, null, c.overs(), c.rules(), c.battingTeamId(), c.bowlingTeamId(), c.battingXi(), c.bowlingXi(),
            c.target(), 0, 0, 0, Extras.NONE, c.striker(), c.nonStriker(),
            List.of(BatterLine.arrives(c.striker()), BatterLine.arrives(c.nonStriker())), List.of(BowlerLine.arrives(c.bowler())),
            c.bowler(), null, Over.fresh(), false, false, false, List.of(), List.of()));
    }

    private static boolean validXi(List<UUID> xi, MatchRules rules) {
        return xi != null && xi.size() >= 2 && xi.size() <= rules.playersPerSide() && xi.stream().noneMatch(Objects::isNull) && new HashSet<>(xi).size() == xi.size();
    }

    // ---- a ball --------------------------------------------------------------------------------------------------

    private static Outcome deliver(InningsState s, Deliver command) {
        var blocked = requireActive(s);
        if (blocked != null) {
            return blocked;
        }
        if (s.needsBatter()) {
            return reject(RuleViolation.NEEDS_NEW_BATTER, "Choose the next batter before the next ball");
        }
        if (s.needsBowler()) {
            return reject(RuleViolation.NEEDS_NEW_BOWLER, "Choose the bowler of the next over before the next ball");
        }
        var d = command.delivery();
        if (d == null) {
            return reject(RuleViolation.INVALID_DELIVERY, "A delivery is required");
        }
        var bad = checkDelivery(s, d);
        if (bad != null) {
            return bad;
        }

        var w = new Work(s);
        var kind = d.kind();
        boolean legal = d.isLegal();
        boolean wide = kind == Kind.WIDE;
        boolean noBall = kind == Kind.NO_BALL;
        int penalty = wide ? s.rules().widePenalty() : noBall ? s.rules().noBallPenalty() : 0;
        int bat = !wide && d.runsType() == RunsType.BAT ? d.runs() : 0;
        int byes = !wide && d.runsType() == RunsType.BYE ? d.runs() : 0;
        int legByes = !wide && d.runsType() == RunsType.LEG_BYE ? d.runs() : 0;
        int wideRuns = wide ? penalty + d.runs() : 0;
        int noBallRuns = noBall ? penalty : 0;

        w.runs += bat + byes + legByes + wideRuns + noBallRuns;
        w.wides += wideRuns;
        w.noBalls += noBallRuns;
        w.byes += byes;
        w.legByes += legByes;

        var striker = w.atBatting;
        if (!wide) {
            w.batters.computeIfPresent(striker, (id, b) -> b.scored(bat, true, d.boundary() && bat == 4, d.boundary() && bat == 6));
        }
        w.bowlers.computeIfPresent(w.currentBowler, (id, b) -> b.delivered(legal ? 1 : 0, bat + wideRuns + noBallRuns, wide ? 1 : 0, noBall ? 1 : 0));
        if (legal) {
            w.legalBalls++;
            w.overLegal++;
        }
        w.overDeliveries++;
        w.addShare(w.currentBowler, legal);
        if (bat > 0 || !legal) {
            w.maidenBroken = true;
        }
        if (noBall && s.rules().freeHitEnabled()) {
            w.freeHit = true;
        } else if (legal) {
            w.freeHit = false;
        }

        var dismissal = d.dismissal();
        if (dismissal != null && dismissal.type() == Dismissal.Type.RUN_OUT) {
            var out = dismissal.batterOut();
            var survivor = out.equals(w.atBatting) ? w.atBowlers : w.atBatting;
            w.atBatting = null;
            w.atBowlers = null;
            if (dismissal.end() == End.BATTING_END) {
                w.atBowlers = survivor;
            } else {
                w.atBatting = survivor;
            }
            w.dismiss(out, dismissal.type());
        } else {
            w.rotate(d.boundary() ? 0 : d.runs());
            if (dismissal != null) {
                var out = dismissal.batterOut() == null ? striker : dismissal.batterOut();
                w.vacate(out);
                w.dismiss(out, dismissal.type());
            }
        }

        if (legal && w.overLegal == s.rules().ballsPerOver()) {
            w.finishOver();
        }
        if (dismissal != null) {
            w.afterRemoval();
        }
        w.finish(true);
        return new Outcome.Accepted(w.build());
    }

    private static Outcome checkDelivery(InningsState s, Delivery d) {
        if (d.runs() < 0 || d.runs() > MAX_RUNS) {
            return reject(RuleViolation.RUNS_OUT_OF_RANGE, "Runs on a ball are between 0 and " + MAX_RUNS);
        }
        if (d.boundary()) {
            boolean four = d.runs() == 4;
            boolean six = d.runs() == 6 && d.kind() != Kind.WIDE && d.runsType() == RunsType.BAT;
            if (!(four || six) || d.dismissal() != null) {
                return reject(RuleViolation.INVALID_BOUNDARY, "A boundary is a four, or a six off the bat, and is not a dismissal");
            }
        }
        var dismissal = d.dismissal();
        if (dismissal == null) {
            return null;
        }
        if (dismissal.type() == null) {
            return reject(RuleViolation.INVALID_DELIVERY, "A dismissal needs a type");
        }
        var type = dismissal.type();
        var notAllowed = DismissalRules.violation(d.kind(), s.freeHit(), type);
        if (notAllowed.isPresent()) {
            return reject(notAllowed.get(), type + " cannot happen on this ball");
        }
        var striker = s.atBattingEnd();
        if (DismissalRules.eitherBatterCanBeOut(type)) {
            if (dismissal.batterOut() == null) {
                return reject(RuleViolation.DISMISSED_BATTER_REQUIRED, "Say which batter is out");
            }
            if (!dismissal.batterOut().equals(s.atBattingEnd()) && !dismissal.batterOut().equals(s.atBowlersEnd())) {
                return reject(RuleViolation.BATTER_NOT_AT_CREASE, "That batter is not at the crease");
            }
        } else if (dismissal.batterOut() != null && !dismissal.batterOut().equals(striker)) {
            return reject(RuleViolation.ONLY_STRIKER_CAN_BE_OUT, type + " can only dismiss the striker");
        }
        if (type == Dismissal.Type.RUN_OUT && dismissal.end() == null) {
            return reject(RuleViolation.RUN_OUT_END_REQUIRED, "Say at which end the batter was run out");
        }
        if (!DismissalRules.keepsCompletedRuns(type) && d.runs() != 0) {
            return reject(RuleViolation.DISMISSAL_RUNS_NOT_ALLOWED, type + " scores no runs off the ball");
        }
        return null;
    }

    // ---- batters -------------------------------------------------------------------------------------------------

    private static Outcome selectBatter(InningsState s, SelectBatter c) {
        var blocked = requireActive(s);
        if (blocked != null) {
            return blocked;
        }
        if (!s.needsBatter()) {
            return reject(RuleViolation.NO_BATTER_NEEDED, "No batter is needed right now");
        }
        var id = c.batterId();
        if (id == null || !s.battingXi().contains(id)) {
            return reject(RuleViolation.BATTER_NOT_IN_XI, "That player is not in the batting XI");
        }
        if (id.equals(s.atBattingEnd()) || id.equals(s.atBowlersEnd())) {
            return reject(RuleViolation.BATTER_ALREADY_AT_CREASE, "That batter is already at the crease");
        }
        var line = s.batter(id);
        if (line.isPresent() && line.get().status() != BatterStatus.RETIRED_HURT) {
            return reject(RuleViolation.BATTER_ALREADY_OUT, "That batter is out and cannot bat again");
        }
        var w = new Work(s);
        if (line.isPresent()) {
            w.batters.computeIfPresent(id, (k, b) -> b.with(BatterStatus.NOT_OUT, null, null));
        } else {
            w.batters.put(id, BatterLine.arrives(id));
        }
        if (w.atBatting == null) {
            w.atBatting = id;
        } else {
            w.atBowlers = id;
        }
        w.needsBatter = false;
        w.finish(false);
        return new Outcome.Accepted(w.build());
    }

    private static Outcome retire(InningsState s, RetireBatter c) {
        var blocked = requireActive(s);
        if (blocked != null) {
            return blocked;
        }
        if (s.needsBatter()) {
            return reject(RuleViolation.NEEDS_NEW_BATTER, "Choose the next batter first");
        }
        if (c.type() == null) {
            return reject(RuleViolation.INVALID_RETIREMENT, "Say whether the batter retired hurt or out");
        }
        var id = c.batterId();
        if (id == null || !(id.equals(s.atBattingEnd()) || id.equals(s.atBowlersEnd()))) {
            return reject(RuleViolation.BATTER_NOT_AT_CREASE, "That batter is not at the crease");
        }
        var w = new Work(s);
        w.vacate(id);
        if (c.type() == ScoringCommand.RetirementType.HURT) {
            w.batters.computeIfPresent(id, (k, b) -> b.with(BatterStatus.RETIRED_HURT, null, null));
        } else {
            w.wickets++;
            w.batters.computeIfPresent(id, (k, b) -> b.with(BatterStatus.RETIRED_OUT, null, null));
            w.fow.add(new FallOfWicket(w.wickets, w.runs, id, null, w.legalBalls));
        }
        w.afterRemoval();
        w.finish(false);
        return new Outcome.Accepted(w.build());
    }

    // ---- bowlers -------------------------------------------------------------------------------------------------

    private static Outcome selectBowler(InningsState s, SelectBowler c) {
        var blocked = requireActive(s);
        if (blocked != null) {
            return blocked;
        }
        if (!s.needsBowler()) {
            return reject(RuleViolation.NO_BOWLER_NEEDED, "The over is not finished, so no new bowler is needed");
        }
        var refused = checkBowler(s, c.bowlerId(), Set.of());
        if (refused != null) {
            return refused;
        }
        var w = new Work(s);
        var noted = recordWaiver(s, c.bowlerId(), Set.of(), w);
        w.currentBowler = c.bowlerId();
        w.bowlers.putIfAbsent(c.bowlerId(), BowlerLine.arrives(c.bowlerId()));
        w.needsBowler = false;
        return new Outcome.Accepted(w.build(), noted);
    }

    private static Outcome changeBowlerMidOver(InningsState s, ChangeBowlerMidOver c) {
        var blocked = requireActive(s);
        if (blocked != null) {
            return blocked;
        }
        if (s.needsBowler()) {
            return reject(RuleViolation.NEEDS_NEW_BOWLER, "The over is finished: choose the bowler of the next over");
        }
        if (s.needsBatter()) {
            return reject(RuleViolation.NEEDS_NEW_BATTER, "Choose the next batter first");
        }
        if (s.over().deliveries() == 0) {
            return reject(RuleViolation.NO_DELIVERY_IN_OVER, "No ball has been bowled in this over yet");
        }
        var id = c.newBowlerId();
        if (id != null && id.equals(s.currentBowler())) {
            return reject(RuleViolation.SAME_BOWLER, "That bowler is already bowling");
        }
        var inOver = new HashSet<UUID>();
        s.over().shares().forEach(share -> inOver.add(share.bowlerId()));
        if (id != null && inOver.contains(id)) {
            return reject(RuleViolation.BOWLER_ALREADY_BOWLED_THIS_OVER, "That bowler has already bowled in this over");
        }
        var refused = checkBowler(s, id, inOver);
        if (refused != null) {
            return refused;
        }
        var w = new Work(s);
        var noted = recordWaiver(s, id, inOver, w);
        w.currentBowler = id;
        w.bowlers.putIfAbsent(id, BowlerLine.arrives(id));
        return new Outcome.Accepted(w.build(), noted);
    }

    /** Notes on the innings, and on the command's event, when this bowler is allowed only because a rule was waived as a last resort. */
    private static List<Waiver> recordWaiver(InningsState s, UUID bowler, Set<UUID> excluded, Work w) {
        var kind = eligibility(s, excluded).waiver();
        if (kind == null) {
            return List.of();
        }
        var waiver = new Waiver(s.legalBalls() / s.rules().ballsPerOver(), bowler, kind);
        w.waivers.add(waiver);
        return List.of(waiver);
    }

    private static Outcome checkBowler(InningsState s, UUID id, Set<UUID> excluded) {
        if (id == null || !s.bowlingXi().contains(id)) {
            return reject(RuleViolation.BOWLER_NOT_IN_XI, "That player is not in the bowling XI");
        }
        if (!eligibleBowlers(s, excluded).contains(id)) {
            return id.equals(s.previousOverBowler())
                ? reject(RuleViolation.BOWLER_BOWLED_PREVIOUS_OVER, "A bowler cannot bowl two overs in a row")
                : reject(RuleViolation.BOWLER_LIMIT_REACHED, "That bowler has bowled the most overs allowed");
        }
        return null;
    }

    /**
     * Who may bowl next. Not the bowler of the previous over, and not one who has reached the limit. If that leaves nobody, the limit is waived so the
     * innings can go on; the rule against two overs in a row is waived only when no other bowler is left.
     */
    public static List<UUID> eligibleBowlers(InningsState s, Set<UUID> excluded) {
        return eligibility(s, excluded).bowlers();
    }

    private record Eligibility(List<UUID> bowlers, WaiverKind waiver) {
    }

    private static Eligibility eligibility(InningsState s, Set<UUID> excluded) {
        int limit = s.rules().bowlerLimit(s.overs());
        var pool = s.bowlingXi().stream().filter(b -> !excluded.contains(b)).toList();
        var notPrevious = pool.stream().filter(b -> !b.equals(s.previousOverBowler())).toList();
        var underLimit = notPrevious.stream().filter(b -> s.bowler(b).map(BowlerLine::creditedOvers).orElse(0) < limit).toList();
        if (!underLimit.isEmpty()) {
            return new Eligibility(underLimit, null);
        }
        return notPrevious.isEmpty() ? new Eligibility(pool, WaiverKind.CONSECUTIVE_OVER) : new Eligibility(notPrevious, WaiverKind.OVER_LIMIT);
    }

    public static List<UUID> eligibleBowlers(InningsState s) {
        return eligibleBowlers(s, Set.of());
    }

    // ---- target and ending ---------------------------------------------------------------------------------------

    private static Outcome reviseTarget(InningsState s, ReviseTarget c) {
        if (s.status() == Status.NOT_STARTED) {
            return reject(RuleViolation.INNINGS_NOT_STARTED, "The innings has not started");
        }
        if (s.target() == null) {
            return reject(RuleViolation.NO_TARGET_TO_REVISE, "Only a chase has a target");
        }
        if (c.target() < 1) {
            return reject(RuleViolation.INVALID_TARGET, "The target must be at least 1");
        }
        if (s.status() == Status.COMPLETE && s.endReason() == EndReason.TARGET_REACHED && s.runs() < c.target()) {
            return reject(RuleViolation.TARGET_ALREADY_REACHED, "The chase was already won at the old target");
        }
        var w = new Work(s);
        w.target = c.target();
        w.finish(false);
        return new Outcome.Accepted(w.build());
    }

    private static Outcome endInnings(InningsState s, EndInnings c) {
        var blocked = requireActive(s);
        if (blocked != null) {
            return blocked;
        }
        if (c.reason() != EndReason.DECLARED && c.reason() != EndReason.NO_BATTERS_LEFT) {
            return reject(RuleViolation.CANNOT_END_YET, "An innings can be ended by the scorer only as declared or with no batters left");
        }
        if (c.reason() == EndReason.NO_BATTERS_LEFT && !s.yetToBat().isEmpty()) {
            return reject(RuleViolation.CANNOT_END_YET, "There are still batters who have not batted");
        }
        var w = new Work(s);
        w.complete(c.reason());
        return new Outcome.Accepted(w.build());
    }

    private static Outcome requireActive(InningsState s) {
        return switch (s.status()) {
            case NOT_STARTED -> reject(RuleViolation.INNINGS_NOT_STARTED, "The innings has not started");
            case COMPLETE -> reject(RuleViolation.INNINGS_COMPLETE, "The innings is complete");
            case IN_PROGRESS -> null;
        };
    }

    private static Outcome.Rejected reject(RuleViolation violation, String message) {
        return new Outcome.Rejected(violation, message);
    }

    // ---- working copy --------------------------------------------------------------------------------------------

    /** A mutable copy of a state for one command. It never escapes: {@code build()} makes the new immutable state. */
    private static final class Work {
        private final InningsState base;
        Status status;
        EndReason endReason;
        Integer target;
        int runs;
        int wickets;
        int legalBalls;
        int wides;
        int noBalls;
        int byes;
        int legByes;
        UUID atBatting;
        UUID atBowlers;
        final LinkedHashMap<UUID, BatterLine> batters = new LinkedHashMap<>();
        final LinkedHashMap<UUID, BowlerLine> bowlers = new LinkedHashMap<>();
        UUID currentBowler;
        UUID previousOverBowler;
        int overDeliveries;
        int overLegal;
        final List<OverShare> shares;
        boolean maidenBroken;
        boolean freeHit;
        boolean needsBatter;
        boolean needsBowler;
        final List<FallOfWicket> fow;
        final List<Waiver> waivers;

        Work(InningsState s) {
            this.base = s;
            status = s.status();
            endReason = s.endReason();
            target = s.target();
            runs = s.runs();
            wickets = s.wickets();
            legalBalls = s.legalBalls();
            wides = s.extras().wides();
            noBalls = s.extras().noBalls();
            byes = s.extras().byes();
            legByes = s.extras().legByes();
            atBatting = s.atBattingEnd();
            atBowlers = s.atBowlersEnd();
            s.batters().forEach(b -> batters.put(b.playerId(), b));
            s.bowlers().forEach(b -> bowlers.put(b.playerId(), b));
            currentBowler = s.currentBowler();
            previousOverBowler = s.previousOverBowler();
            overDeliveries = s.over().deliveries();
            overLegal = s.over().legalBalls();
            shares = new ArrayList<>(s.over().shares());
            maidenBroken = s.over().maidenBroken();
            freeHit = s.freeHit();
            needsBatter = s.needsBatter();
            needsBowler = s.needsBowler();
            fow = new ArrayList<>(s.fallOfWickets());
            waivers = new ArrayList<>(s.waivers());
        }

        void rotate(int crossings) {
            if (crossings % 2 == 1 && atBatting != null && atBowlers != null) {
                swapEnds();
            }
        }

        /** Swaps the batters, except that a lone batter (last man stands) never moves; {@code finish} puts him back on strike. */
        void swapEnds() {
            var t = atBatting;
            atBatting = atBowlers;
            atBowlers = t;
        }

        void vacate(UUID id) {
            if (id.equals(atBatting)) {
                atBatting = null;
            } else if (id.equals(atBowlers)) {
                atBowlers = null;
            }
        }

        void dismiss(UUID id, Dismissal.Type type) {
            wickets++;
            boolean credited = DismissalRules.creditedToBowler(type);
            batters.computeIfPresent(id, (k, b) -> b.with(BatterStatus.OUT, type, credited ? currentBowler : null));
            fow.add(new FallOfWicket(wickets, runs, id, type, legalBalls));
            if (credited) {
                bowlers.computeIfPresent(currentBowler, (k, b) -> b.tookWicket());
            }
        }

        void addShare(UUID bowler, boolean legal) {
            for (int i = 0; i < shares.size(); i++) {
                if (shares.get(i).bowlerId().equals(bowler)) {
                    shares.set(i, new OverShare(bowler, shares.get(i).legalBalls() + (legal ? 1 : 0)));
                    return;
                }
            }
            shares.add(new OverShare(bowler, legal ? 1 : 0));
        }

        /** The over counts for the bowler of most legal balls; a tie goes to whoever bowled first. Then the batters swap and a new bowler is needed. */
        void finishOver() {
            var credited = shares.get(0);
            for (var share : shares) {
                if (share.legalBalls() > credited.legalBalls()) {
                    credited = share;
                }
            }
            boolean maiden = !maidenBroken;
            bowlers.computeIfPresent(credited.bowlerId(), (k, b) -> b.creditedOver(maiden));
            swapEnds();
            previousOverBowler = currentBowler;
            overDeliveries = 0;
            overLegal = 0;
            shares.clear();
            maidenBroken = false;
            needsBowler = true;
        }

        /** After a batter has gone: ask for the next one if there is anybody who can bat, otherwise the innings is over (or the lone batter carries on). */
        void afterRemoval() {
            var atCrease = new HashSet<UUID>();
            if (atBatting != null) {
                atCrease.add(atBatting);
            }
            if (atBowlers != null) {
                atCrease.add(atBowlers);
            }
            boolean someoneCanBat = base.battingXi().stream().anyMatch(p -> !atCrease.contains(p)
                && (!batters.containsKey(p) || batters.get(p).status() == BatterStatus.RETIRED_HURT));
            if (someoneCanBat) {
                needsBatter = true;
            } else if (atCrease.isEmpty() || !base.rules().lastManStands()) {
                complete(EndReason.ALL_OUT);
            }
        }

        void complete(EndReason reason) {
            status = Status.COMPLETE;
            endReason = reason;
            needsBatter = false;
            needsBowler = false;
        }

        /**
         * The end-of-ball checks: the target, the last over, and a lone batter standing at the batting end. On the ball just bowled, winning the chase
         * counts even if the same ball also bowled the side out.
         */
        void finish(boolean afterBall) {
            boolean reached = target != null && runs >= target;
            if (reached && (status == Status.IN_PROGRESS || (afterBall && status == Status.COMPLETE))) {
                complete(EndReason.TARGET_REACHED);
            } else if (status == Status.IN_PROGRESS && legalBalls == base.overs() * base.rules().ballsPerOver()) {
                complete(EndReason.OVERS_COMPLETE);
            }
            if (status == Status.IN_PROGRESS && !needsBatter && atBatting == null && atBowlers != null) {
                atBatting = atBowlers;
                atBowlers = null;
            }
        }

        InningsState build() {
            return new InningsState(status, endReason, base.overs(), base.rules(), base.battingTeamId(), base.bowlingTeamId(), base.battingXi(), base.bowlingXi(), target,
                runs, wickets, legalBalls, new Extras(wides, noBalls, byes, legByes), atBatting, atBowlers,
                new ArrayList<>(batters.values()), new ArrayList<>(bowlers.values()), currentBowler, previousOverBowler,
                new Over(overDeliveries, overLegal, shares, maidenBroken), freeHit, needsBatter, needsBowler, fow, waivers);
        }
    }
}
