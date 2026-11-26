package com.dsports.cricket.application.usecase;

import com.dsports.cricket.application.result.PublicScorecard;
import com.dsports.cricket.application.result.ScoringView;
import com.dsports.cricket.domain.model.Match;
import com.dsports.cricket.domain.rules.InningsState;
import com.dsports.cricket.domain.scoring.MatchScore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Turns a stored {@link MatchScore} into the {@link PublicScorecard}. It reuses the scorer's innings view (so the two never disagree about a dismissal or a bowling
 * figure) and then drops every id: only names and numbers leave. The rates are worked out here, once, so no client holds a cricket rule.
 */
final class PublicScorecardBuilder {

    private PublicScorecardBuilder() {
    }

    /** {@code linkable}: whether a player id may appear (the player has a public page). Everyone else shows a name only, with no id to follow. */
    static PublicScorecard build(Match match, int seq, MatchScore score, ScoringViewBuilder.Names names, Predicate<UUID> linkable) {
        var innings = new ArrayList<PublicScorecard.Innings>();
        for (int i = 0; i < score.innings().size(); i++) {
            var state = score.innings().get(i);
            innings.add(innings(ScoringViewBuilder.inningsView(i + 1, state, List.of(), names), state, linkable));
        }
        var status = score.status();
        var result = ScoringViewBuilder.result(score, names);
        var settings = match.getSettings();
        return new PublicScorecard(match.getId(), seq, status.name(), status.isLive(), names.team().apply(match.getTeamAId()), names.team().apply(match.getTeamBId()), settings.overs(),
            settings.city(), match.getVenue() == null ? null : match.getVenue().name(), settings.scheduledAt(), innings,
            result == null ? null : new PublicScorecard.Result(result.type(), result.winner() == null ? null : result.winner().name(), result.text()));
    }

    private static PublicScorecard.Innings innings(ScoringView.Innings v, InningsState state, Predicate<UUID> linkable) {
        int bpo = state.rules().ballsPerOver();
        boolean playing = !state.isComplete();
        var requiredRate = state.target() != null && playing ? rate(Math.max(0, state.target() - state.runs()), state.ballsRemaining(), bpo) : null;
        var batters = v.batters().stream().map(b -> new PublicScorecard.Batter(linkable.test(b.player().id()) ? b.player().id() : null, b.player().name(), b.runs(), b.balls(), b.fours(), b.sixes(), b.howOut(), b.notOut(), b.atCrease() && playing,
            v.striker() != null && playing && b.player().id().equals(v.striker().id()))).toList();
        var bowlers = v.bowlers().stream().map(b -> new PublicScorecard.Bowler(linkable.test(b.player().id()) ? b.player().id() : null, b.player().name(), b.overs(), b.maidens(), b.runs(), b.wickets(), b.wides(), b.noBalls(), b.economy(),
            v.bowler() != null && playing && b.player().id().equals(v.bowler().id()))).toList();
        var falls = v.fallOfWickets().stream().map(f -> new PublicScorecard.Fall(f.number(), f.runs(), f.batter().name(), f.overs(), f.howOut())).toList();
        var extras = v.extras();
        return new PublicScorecard.Innings(v.number(), v.battingTeam().name(), v.bowlingTeam().name(), v.status(), v.endReason(), v.runs(), v.wickets(), v.overs(), v.ballsRemaining(),
            v.target(), v.runsNeeded(), rate(state.runs(), state.legalBalls(), bpo), requiredRate,
            new PublicScorecard.Extras(extras.wides(), extras.noBalls(), extras.byes(), extras.legByes(), extras.total()), batters,
            v.yetToBat().stream().map(ScoringView.Ref::name).toList(), bowlers, falls, v.waivers().stream().map(w -> new PublicScorecard.Waiver(w.over(), w.text())).toList());
    }

    /** Runs per over, to two places; null when no ball has been bowled (or none is left), because there is no rate then. */
    static BigDecimal rate(int runs, int balls, int ballsPerOver) {
        if (balls <= 0) {
            return null;
        }
        return BigDecimal.valueOf(runs).multiply(BigDecimal.valueOf(ballsPerOver)).divide(BigDecimal.valueOf(balls), 2, RoundingMode.HALF_UP);
    }
}
