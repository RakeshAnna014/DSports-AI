package com.dsports.cricket.application.usecase;

import com.dsports.cricket.application.port.PlayerRepository;
import com.dsports.cricket.application.port.TeamRepository;
import com.dsports.cricket.domain.model.Match;
import com.dsports.cricket.domain.model.Player;
import com.dsports.cricket.domain.model.Team;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.UUID;
import java.util.function.Function;

/** Looks up the team and player names of one match, so a view can show names where the engine only has ids. A name that cannot be found reads "Player" or "Team A"/"Team B". */
final class NameLookup {

    private NameLookup() {
    }

    /** The names of the match's two teams, by team id. */
    static Mono<Function<UUID, String>> teamNames(Match match, TeamRepository teams) {
        return Mono.zip(teams.findById(match.getTeamAId()).map(Team::getName).defaultIfEmpty("Team A"), teams.findById(match.getTeamBId()).map(Team::getName).defaultIfEmpty("Team B"))
            .map(t -> id -> id.equals(match.getTeamAId()) ? t.getT1() : t.getT2());
    }

    /** The names for {@code match}; {@code actor} says how the person who made a change is shown (scorer-only views name people, public ones never do). */
    static Mono<ScoringViewBuilder.Names> forMatch(Match match, TeamRepository teams, PlayerRepository players, Function<UUID, String> actor) {
        var playerIds = new HashSet<UUID>();
        playerIds.addAll(match.playingXi(match.getTeamAId()));
        playerIds.addAll(match.playingXi(match.getTeamBId()));
        return Mono.zip(players.findAllById(playerIds).collectMap(Player::getId, Player::getName), teamNames(match, teams))
            .map(t -> new ScoringViewBuilder.Names(id -> t.getT1().getOrDefault(id, "Player"), t.getT2(), actor));
    }
}
