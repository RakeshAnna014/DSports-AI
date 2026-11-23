package com.dsports.cricket.application.usecase;

import com.dsports.cricket.application.port.MatchRepository;
import com.dsports.cricket.application.port.PlayerRepository;
import com.dsports.cricket.application.port.ScoringStore;
import com.dsports.cricket.application.port.TeamRepository;
import com.dsports.cricket.application.result.PublicMatchSummary;
import com.dsports.cricket.application.result.PublicAnalytics;
import com.dsports.cricket.application.result.PublicScorecard;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.Match;
import com.dsports.cricket.domain.scoring.MatchAnalytics;
import com.dsports.cricket.domain.scoring.MatchScore;
import com.dsports.cricket.domain.scoring.MatchScoring;
import com.dsports.cricket.domain.scoring.MatchSetup;
import com.dsports.cricket.domain.scoring.ScoringLog;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * What anyone may read without signing in: the scorecard of a match that has started, and the list of matches being played. There is no actor here on purpose:
 * nothing in this class depends on who is asking, and nothing it returns carries an id other than the match's own. A match that has not started (or that is
 * abandoned before a ball) is simply "not found", exactly like a match that does not exist, so the two cannot be told apart from outside.
 */
public class PublicMatchesUseCase {

    public static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_PAGE = 10_000;
    private static final int MAX_CITY = 80;

    private final MatchRepository matches;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final ScoringStore store;

    public PublicMatchesUseCase(MatchRepository matches, TeamRepository teams, PlayerRepository players, ScoringStore store) {
        this.matches = matches;
        this.teams = teams;
        this.players = players;
        this.store = store;
    }

    /** The match's score as stored, with the seq of the last event; the first is derived from the log when the snapshot is missing, so a lost snapshot never hides a match. */
    private record Stored(int seq, MatchScore score) {
    }

    private record Started(Match match, int seq, MatchScore score) {
    }

    private static CricketDomainException notFound(UUID matchId) {
        return new CricketDomainException(CricketErrorCode.MATCH_NOT_FOUND, "Match not found: " + matchId);
    }

    private Mono<Stored> stored(Match match) {
        return store.snapshot(match.getId()).map(s -> new Stored(s.seq(), s.score()))
            .switchIfEmpty(Mono.defer(() -> store.entries(match.getId()).collectList().filter(entries -> !entries.isEmpty())
                .map(entries -> new Stored(entries.get(entries.size() - 1).seq(), MatchScoring.fold(MatchSetup.from(match), ScoringLog.commands(entries))))));
    }

    private Mono<Started> started(UUID matchId) {
        return matches.findById(matchId).switchIfEmpty(Mono.error(notFound(matchId)))
            .flatMap(match -> stored(match).filter(s -> s.score().hasStarted()).map(s -> new Started(match, s.seq(), s.score())))
            .switchIfEmpty(Mono.error(notFound(matchId)));
    }

    public Mono<PublicScorecard> scorecard(UUID matchId) {
        return started(matchId).flatMap(s -> NameLookup.forMatch(s.match(), teams, players, id -> "A scorer").map(names -> PublicScorecardBuilder.build(s.match(), s.seq(), s.score(), names)));
    }

    /** The charts and commentary of a started match, worked out from its log. A match that has not started is "not found", exactly as for the scorecard. */
    public Mono<PublicAnalytics> analytics(UUID matchId) {
        return started(matchId).flatMap(s -> Mono.zip(NameLookup.forMatch(s.match(), teams, players, id -> "A scorer"), store.entries(matchId).collectList(), store.shots(matchId))
            .map(t -> {
                var analytics = MatchAnalytics.of(MatchSetup.from(s.match()), t.getT2(), t.getT3(), t.getT1().player(), t.getT1().team());
                return new PublicAnalytics(s.seq(), analytics.innings(), analytics.commentary(), analytics.shots());
            }));
    }

    /** Started matches that are being played now, most recently scored first, optionally in one city (any letter case). */
    public Mono<PublicMatchSummary.Page<PublicMatchSummary>> list(String city, int page, int size) {
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_PAGE_SIZE) {
            return Mono.error(new CricketDomainException(CricketErrorCode.VALIDATION_ERROR, "page must be 0 or more and size between 1 and " + MAX_PAGE_SIZE));
        }
        var trimmed = city == null ? null : city.trim();
        if (trimmed != null && trimmed.length() > MAX_CITY) {
            return Mono.error(new CricketDomainException(CricketErrorCode.VALIDATION_ERROR, "The city is too long"));
        }
        var filter = trimmed == null || trimmed.isEmpty() ? null : trimmed;
        return matches.findLive(filter, size + 1, page * size).collectList().flatMap(found -> {
            boolean hasNext = found.size() > size;
            return Flux.fromIterable(found.subList(0, Math.min(size, found.size()))).concatMap(this::summary).collectList()
                .map(items -> new PublicMatchSummary.Page<>(items, page, size, hasNext));
        });
    }

    private Mono<PublicMatchSummary> summary(Match match) {
        return Mono.zip(stored(match).map(Stored::score).defaultIfEmpty(MatchScore.empty()), NameLookup.teamNames(match, teams)).map(t -> {
            var score = t.getT1();
            var teamName = t.getT2();
            var scores = new java.util.ArrayList<PublicMatchSummary.Score>();
            for (int i = 0; i < score.innings().size(); i++) {
                var innings = score.innings().get(i);
                scores.add(new PublicMatchSummary.Score(teamName.apply(innings.battingTeamId()), innings.runs(), innings.wickets(), innings.oversText(), i == score.innings().size() - 1));
            }
            var settings = match.getSettings();
            return new PublicMatchSummary(match.getId(), match.getStatus().name(),teamName.apply(match.getTeamAId()), teamName.apply(match.getTeamBId()), settings.overs(),
                settings.city(), match.getVenue() == null ? null : match.getVenue().name(), settings.scheduledAt(), List.copyOf(scores));
        });
    }
}
