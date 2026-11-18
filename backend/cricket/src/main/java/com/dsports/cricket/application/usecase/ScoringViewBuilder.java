package com.dsports.cricket.application.usecase;

import com.dsports.cricket.application.result.ScoringView;
import com.dsports.cricket.domain.rules.Delivery;
import com.dsports.cricket.domain.rules.DismissalRules;
import com.dsports.cricket.domain.rules.InningsState;
import com.dsports.cricket.domain.rules.Scorecard;
import com.dsports.cricket.domain.rules.ScoringCommand;
import com.dsports.cricket.domain.rules.ScoringEngine;
import com.dsports.cricket.domain.scoring.EditWindow;
import com.dsports.cricket.domain.scoring.LogEntry;
import com.dsports.cricket.domain.scoring.MatchScore;
import com.dsports.cricket.domain.scoring.MatchScoring;
import com.dsports.cricket.domain.scoring.MatchSetup;
import com.dsports.cricket.domain.scoring.ScoreCommand;
import com.dsports.cricket.domain.scoring.ScoringLog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Turns the effective log into the {@link ScoringView} the scorer screen shows. Everything on it is computed from the log by the domain; nothing is stored. */
final class ScoringViewBuilder {

    private static final int RECENT_EVENTS = 12;

    /** How ids are shown. A missing name reads "Player", never an id. */
    record Names(Function<UUID, String> player, Function<UUID, String> team, Function<UUID, String> actor) {
    }

    private ScoringViewBuilder() {
    }

    static ScoringView build(UUID matchId, MatchSetup setup, Names names, List<LogEntry> entries, EditWindow window, Instant now) {
        var live = ScoringLog.effective(entries);
        var score = MatchScoring.fold(setup, live.stream().map(LogEntry::command).toList());
        int seq = entries.isEmpty() ? 0 : entries.get(entries.size() - 1).seq();
        var current = score.current();

        var innings = new ArrayList<ScoringView.Innings>();
        for (int i = 0; i < score.innings().size(); i++) {
            var state = score.innings().get(i);
            boolean isCurrent = i == score.innings().size() - 1;
            innings.add(inningsView(i + 1, state, isCurrent ? thisOver(live, state) : List.of(), names));
        }

        String needs = current == null ? null : current.needsBatter() ? "BATTER" : current.needsBowler() ? "BOWLER" : null;
        boolean waiverRequired = current != null && current.needsBowler() && ScoringEngine.waiverNeeded(current, Set.of()) != null;
        int limit = setup.rules().bowlerLimit(setup.overs());
        var rules = new ScoringView.Rules(setup.overs(), setup.rules().ballsPerOver(), setup.rules().playersPerSide(), limit);
        return new ScoringView(matchId, seq, score.status().name(), rules, innings, needs, current != null && current.freeHit(), allowedDismissals(current),
            eligibleBatters(current, names), bowlerChoices(current, limit, names), waiverRequired, result(score, names), canUndo(entries, live, score, setup, window, now, names),
            window.editableUntil(), window.isLocked(now), recentEvents(entries, names));
    }

    // ---- innings ------------------------------------------------------------------------------------------------

    private static ScoringView.Ref ref(UUID id, Names names) {
        return id == null ? null : new ScoringView.Ref(id, names.player().apply(id));
    }

    private static ScoringView.Innings inningsView(int number, InningsState s, List<String> thisOver, Names names) {
        int bpo = s.rules().ballsPerOver();
        var card = Scorecard.of(s);
        var extras = s.extras();
        var batters = new ArrayList<ScoringView.Batter>();
        for (var line : s.batters()) {
            boolean atCrease = line.playerId().equals(s.atBattingEnd()) || line.playerId().equals(s.atBowlersEnd());
            batters.add(new ScoringView.Batter(ref(line.playerId(), names), line.runs(), line.balls(), line.fours(), line.sixes(), Scorecard.howOut(line, names.player()),
                line.status() == InningsState.BatterStatus.NOT_OUT || line.status() == InningsState.BatterStatus.RETIRED_HURT, atCrease));
        }
        var bowlers = card.bowlers().stream().map(b -> new ScoringView.Bowler(ref(b.line().playerId(), names), b.overs(), b.line().creditedOvers(), b.line().maidens(),
            b.line().runs(), b.line().wickets(), b.line().wides(), b.line().noBalls(), b.economy())).toList();
        var falls = card.fallOfWickets().stream().map(f -> new ScoringView.Fall(f.fall().number(), f.fall().runs(), ref(f.fall().batterId(), names), f.overs(),
            s.batter(f.fall().batterId()).map(l -> Scorecard.howOut(l, names.player())).orElse(""))).toList();
        var waivers = s.waivers().stream().map(w -> new ScoringView.WaiverNote(w.over() + 1, ref(w.bowlerId(), names), w.kind().name(), waiverText(w, names))).toList();
        return new ScoringView.Innings(number, new ScoringView.Ref(s.battingTeamId(), names.team().apply(s.battingTeamId())),
            new ScoringView.Ref(s.bowlingTeamId(), names.team().apply(s.bowlingTeamId())), s.status().name(), s.endReason() == null ? null : s.endReason().name(),
            s.runs(), s.wickets(), s.oversText(), s.ballsRemaining(), s.target(), s.target() == null ? null : Math.max(0, s.target() - s.runs()),
            new ScoringView.Extras(extras.wides(), extras.noBalls(), extras.byes(), extras.legByes(), extras.total()), ref(s.striker(), names), ref(s.nonStriker(), names),
            ref(s.currentBowler(), names), thisOver, batters, s.yetToBat().stream().map(id -> ref(id, names)).toList(), bowlers, falls, waivers);
    }

    private static String waiverText(InningsState.Waiver w, Names names) {
        var who = names.player().apply(w.bowlerId());
        return switch (w.kind()) {
            case OVER_LIMIT -> who + " bowled over " + (w.over() + 1) + " beyond the over limit because every other bowler was at it";
            case CONSECUTIVE_OVER -> who + " bowled two overs in a row in over " + (w.over() + 1) + " because no other bowler was left";
        };
    }

    /** The balls of the over in progress, or of the over just finished while a new bowler is awaited. Empty at the start of a fresh over. */
    private static List<String> thisOver(List<LogEntry> live, InningsState state) {
        if (state.over().deliveries() == 0 && !state.needsBowler()) {
            return List.of();
        }
        var deliveries = new ArrayList<Delivery>();
        for (int i = live.size() - 1; i >= 0; i--) {
            var command = live.get(i).command();
            if (command.type() == ScoreCommand.Type.START_INNINGS) {
                break;
            }
            if (command instanceof ScoreCommand.Play p && p.command() instanceof ScoringCommand.Deliver d) {
                deliveries.add(0, d.delivery());
            }
        }
        int bpo = state.rules().ballsPerOver();
        var segments = new ArrayList<List<Delivery>>();
        var segment = new ArrayList<Delivery>();
        int legal = 0;
        for (var d : deliveries) {
            segment.add(d);
            if (d.kind() == Delivery.Kind.NORMAL && ++legal == bpo) {
                segments.add(segment);
                segment = new ArrayList<>();
                legal = 0;
            }
        }
        var chosen = state.needsBowler() ? (segments.isEmpty() ? List.<Delivery>of() : segments.get(segments.size() - 1)) : segment;
        return chosen.stream().map(ScoringViewBuilder::chip).toList();
    }

    /** The short label of a ball on the "this over" strip: a dot, a number, W, Wd, Nb, B, Lb. */
    static String chip(Delivery d) {
        var label = switch (d.kind()) {
            case WIDE -> "Wd" + (d.runs() > 0 ? "+" + d.runs() : "");
            case NO_BALL -> "Nb" + (d.runs() > 0 ? "+" + d.runs() : "");
            case NORMAL -> switch (d.runsType()) {
                case BAT -> d.runs() == 0 ? "•" : String.valueOf(d.runs());
                case BYE -> "B" + d.runs();
                case LEG_BYE -> "Lb" + d.runs();
            };
        };
        return d.dismissal() == null ? label : (label.equals("•") ? "W" : label + "W");
    }

    // ---- what the next ball and the next choice may be ----------------------------------------------------------

    private static Map<String, List<String>> allowedDismissals(InningsState current) {
        boolean freeHit = current != null && current.freeHit();
        var result = new LinkedHashMap<String, List<String>>();
        for (var kind : Delivery.Kind.values()) {
            result.put(kind.name(), java.util.Arrays.stream(com.dsports.cricket.domain.rules.Dismissal.Type.values()).filter(t -> DismissalRules.isAllowed(kind, freeHit, t)).map(Enum::name).toList());
        }
        return result;
    }

    private static List<ScoringView.BatterChoice> eligibleBatters(InningsState current, Names names) {
        if (current == null || !current.needsBatter()) {
            return List.of();
        }
        var choices = new ArrayList<ScoringView.BatterChoice>();
        for (var id : current.battingXi()) {
            if (id.equals(current.atBattingEnd()) || id.equals(current.atBowlersEnd())) {
                continue;
            }
            var line = current.batter(id);
            if (line.isEmpty()) {
                choices.add(new ScoringView.BatterChoice(ref(id, names), false, null));
            } else if (line.get().status() == InningsState.BatterStatus.RETIRED_HURT) {
                choices.add(new ScoringView.BatterChoice(ref(id, names), true, "retired hurt, " + line.get().runs() + " off " + line.get().balls()));
            }
        }
        return choices;
    }

    private static List<ScoringView.BowlerChoice> bowlerChoices(InningsState current, int limit, Names names) {
        if (current == null) {
            return List.of();
        }
        var inOver = new HashSet<UUID>();
        current.over().shares().forEach(share -> inOver.add(share.bowlerId()));
        var next = current.needsBowler() ? ScoringEngine.eligibleBowlers(current) : List.<UUID>of();
        boolean midOver = !current.needsBowler() && !current.needsBatter() && current.over().deliveries() > 0 && !current.isComplete();
        var replace = midOver ? ScoringEngine.eligibleBowlers(current, inOver) : List.<UUID>of();
        var choices = new ArrayList<ScoringView.BowlerChoice>();
        for (var id : current.bowlingXi()) {
            var line = current.bowler(id);
            int credited = line.map(InningsState.BowlerLine::creditedOvers).orElse(0);
            boolean canNext = next.contains(id);
            boolean canReplace = replace.contains(id) && !id.equals(current.currentBowler());
            String why = null;
            if (current.needsBowler() && !canNext) {
                why = id.equals(current.previousOverBowler()) ? "BOWLED_LAST_OVER" : "AT_LIMIT";
            }
            choices.add(new ScoringView.BowlerChoice(ref(id, names), InningsState.oversText(line.map(InningsState.BowlerLine::legalBalls).orElse(0), current.rules().ballsPerOver()),
                credited, limit, canNext, canReplace, why));
        }
        return choices;
    }

    // ---- the result ---------------------------------------------------------------------------------------------

    private static ScoringView.Result result(MatchScore score, Names names) {
        if (score.endedBy() != null) {
            return new ScoringView.Result(score.endedBy().name(), null, 0, 0, score.endedBy() == ScoreCommand.EndMatch.Reason.ABANDONED ? "Match abandoned" : "No result");
        }
        var result = score.result();
        if (result.isEmpty()) {
            return null;
        }
        var r = result.get();
        var winner = r.winnerTeamId() == null ? null : new ScoringView.Ref(r.winnerTeamId(), names.team().apply(r.winnerTeamId()));
        var text = switch (r.type()) {
            case WON_BY_WICKETS -> winner.name() + " won by " + plural(r.margin(), "wicket") + " (" + plural(r.ballsRemaining(), "ball") + " left)";
            case WON_BY_RUNS -> winner.name() + " won by " + plural(r.margin(), "run");
            case TIE -> "Match tied";
            case IN_PROGRESS -> "";
        };
        return new ScoringView.Result(r.type().name(), winner, r.margin(), r.ballsRemaining(), text);
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    // ---- undo and events ----------------------------------------------------------------------------------------

    private static ScoringView.CanUndo canUndo(List<LogEntry> entries, List<LogEntry> live, MatchScore score, MatchSetup setup, EditWindow window, Instant now, Names names) {
        if (window.isLocked(now)) {
            return new ScoringView.CanUndo(false, null, false, "LOCKED");
        }
        var voids = ScoringLog.planUndo(entries, ScoringLog.UndoMode.BALL);
        if (voids.isEmpty()) {
            return new ScoringView.CanUndo(false, null, false, "NOTHING_TO_UNDO");
        }
        var voided = new HashSet<>(voids);
        var after = MatchScoring.fold(setup, live.stream().filter(e -> !voided.contains(e.seq())).map(LogEntry::command).toList());
        var anchor = live.stream().filter(e -> e.seq() == voids.get(voids.size() - 1)).findFirst().orElseThrow();
        return new ScoringView.CanUndo(true, "Undo: " + describe(anchor.command(), names), ScoringLog.reopens(score, after), null);
    }

    static List<ScoringView.Event> eventsAfter(List<LogEntry> entries, int afterSeq, Names names) {
        return entries.stream().filter(e -> e.seq() > afterSeq).map(e -> event(e, entries, names)).toList();
    }

    private static List<ScoringView.Event> recentEvents(List<LogEntry> entries, Names names) {
        int from = Math.max(0, entries.size() - RECENT_EVENTS);
        return entries.subList(from, entries.size()).stream().map(e -> event(e, entries, names)).toList();
    }

    private static ScoringView.Event event(LogEntry e, List<LogEntry> entries, Names names) {
        var by = new ScoringView.Ref(e.actorId(), names.actor().apply(e.actorId()));
        return switch (e.kind()) {
            case COMMAND -> new ScoringView.Event(e.seq(), e.command().type().name(), describe(e.command(), names), by, e.at());
            case UNDO -> {
                var undone = new ArrayList<String>();
                for (int seq : e.voids()) {
                    entries.stream().filter(x -> x.seq() == seq && x.command() != null).findFirst().ifPresent(x -> undone.add(describe(x.command(), names)));
                }
                yield new ScoringView.Event(e.seq(), "UNDO", "Undid: " + String.join("; ", undone), by, e.at());
            }
            case ADMIN_UNLOCK -> new ScoringView.Event(e.seq(), "ADMIN_UNLOCK", "Reopened by an administrator: " + e.reason(), by, e.at());
        };
    }

    /** One line about a command, for the event list and the undo button. */
    static String describe(ScoreCommand command, Names names) {
        var who = names.player();
        return switch (command) {
            case ScoreCommand.StartInnings c -> "innings started: " + who.apply(c.striker()) + " and " + who.apply(c.nonStriker()) + " open, " + who.apply(c.bowler()) + " bowls";
            case ScoreCommand.EndMatch c -> c.reason() == ScoreCommand.EndMatch.Reason.ABANDONED ? "match abandoned" : "no result";
            case ScoreCommand.Play p -> switch (p.command()) {
                case ScoringCommand.Deliver d -> ball(d.delivery(), who);
                case ScoringCommand.SelectBatter c -> who.apply(c.batterId()) + " comes in";
                case ScoringCommand.SelectBowler c -> who.apply(c.bowlerId()) + " to bowl";
                case ScoringCommand.ChangeBowlerMidOver c -> who.apply(c.newBowlerId()) + " takes over the over";
                case ScoringCommand.RetireBatter c -> who.apply(c.batterId()) + (c.type() == ScoringCommand.RetirementType.HURT ? " retired hurt" : " retired out");
                case ScoringCommand.ReviseTarget c -> "target revised to " + c.target();
                case ScoringCommand.EndInnings c -> c.reason() == InningsState.EndReason.DECLARED ? "innings declared" : "innings closed: no batters left";
                case ScoringCommand.StartInnings c -> "innings started";
            };
        };
    }

    private static String ball(Delivery d, Function<UUID, String> who) {
        var text = switch (d.kind()) {
            case WIDE -> "wide" + (d.runs() > 0 ? " + " + d.runs() : "");
            case NO_BALL -> "no ball" + (d.runs() > 0 ? " + " + d.runs() + (d.runsType() == Delivery.RunsType.BAT ? "" : d.runsType() == Delivery.RunsType.BYE ? " byes" : " leg byes") : "");
            case NORMAL -> switch (d.runsType()) {
                case BAT -> d.runs() == 0 ? "no run" : d.boundary() ? (d.runs() == 4 ? "FOUR" : "SIX") : plural(d.runs(), "run");
                case BYE -> plural(d.runs(), "bye");
                case LEG_BYE -> plural(d.runs(), "leg bye");
            };
        };
        if (d.dismissal() == null) {
            return text;
        }
        var type = d.dismissal().type().name().toLowerCase().replace('_', ' ');
        var batter = d.dismissal().batterOut() == null ? "" : who.apply(d.dismissal().batterOut()) + " ";
        return text + ", wicket: " + batter + type;
    }
}
