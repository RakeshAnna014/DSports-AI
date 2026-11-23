package com.dsports.cricket.application.usecase;

import com.dsports.cricket.application.port.MatchRepository;
import com.dsports.cricket.application.port.PlayerRepository;
import com.dsports.cricket.application.port.ScoringStore;
import com.dsports.cricket.application.result.PublicPlayerStats;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.BallType;
import com.dsports.cricket.domain.scoring.CareerStats;
import com.dsports.cricket.domain.scoring.MatchSetup;
import com.dsports.cricket.domain.scoring.ScoringLog;
import com.dsports.cricket.domain.scoring.MatchScoring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.UUID;

/**
 * Career statistics. Reading needs no actor (the figures are names and numbers only); the rebuild is for administrators, and the route enforces that. The stored rows are
 * a projection of the log: {@link #rebuild} deletes and recomputes every completed match's rows from its log, so the projection can never be the thing that is wrong for long.
 */
public class PlayerStatsUseCase {

    private static final Logger log = LoggerFactory.getLogger(PlayerStatsUseCase.class);
    private static final int BALLS_PER_OVER = 6;

    private final PlayerRepository players;
    private final MatchRepository matches;
    private final ScoringStore store;

    public PlayerStatsUseCase(PlayerRepository players, MatchRepository matches, ScoringStore store) {
        this.players = players;
        this.matches = matches;
        this.store = store;
    }

    /** The career of a player who has played a finished match. An unknown player and one with no finished match are the same 404, so nothing can be learned about who exists. */
    public Mono<PublicPlayerStats> career(UUID playerId) {
        return players.findById(playerId).switchIfEmpty(Mono.error(notFound(playerId))).flatMap(player -> store.statsOf(playerId).collectList().flatMap(rows -> {
            var lines = Arrays.stream(BallType.values()).map(type -> CareerStats.total(playerId, type, rows)).flatMap(java.util.Optional::stream)
                .map(c -> PublicPlayerStats.line(c, BALLS_PER_OVER)).toList();
            return lines.isEmpty() ? Mono.<PublicPlayerStats>error(notFound(playerId)) : Mono.just(new PublicPlayerStats(player.getName(), lines));
        }));
    }

    /** Recomputes the rows of every completed match (and clears any stale ones) from its log and returns how many matches it visited. Each match is done under its own scoring lock, so a ball scored meanwhile waits. */
    public Mono<Integer> rebuild() {
        return store.matchIdsToRebuild().concatMap(matchId -> matches.findById(matchId).flatMap(match -> store.exclusive(matchId, session -> session.entries().collectList().flatMap(entries -> {
            var setup = MatchSetup.from(match);
            var score = MatchScoring.fold(setup, ScoringLog.commands(entries));
            return session.replaceStats(CareerStats.ofMatch(setup, score, match.getSettings().ballType())).thenReturn(1);
        })))).reduce(0, Integer::sum).doOnNext(n -> log.info("Career stats rebuilt for {} completed matches", n));
    }

    private static CricketDomainException notFound(UUID playerId) {
        return new CricketDomainException(CricketErrorCode.PLAYER_NOT_FOUND, "No career found for player " + playerId);
    }
}
