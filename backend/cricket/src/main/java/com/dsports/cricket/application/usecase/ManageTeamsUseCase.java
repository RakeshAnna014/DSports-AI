package com.dsports.cricket.application.usecase;

import com.dsports.cricket.application.port.PlayerRepository;
import com.dsports.cricket.application.port.TeamRepository;
import com.dsports.cricket.application.result.TeamResult;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.Player;
import com.dsports.cricket.domain.model.Team;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Teams. A team can be edited by the person who created it and by the captain's own account; it can be seen by those two and by every member whose player
 * is linked to an account. Anyone else gets "not found". A player can only be added by someone who may use that player (created it, or it is their own
 * profile). Member changes are version-checked through the team row, so two people editing at once cannot silently overwrite each other.
 */
public class ManageTeamsUseCase {

    private final TeamRepository teams;
    private final PlayerRepository players;
    private final Clock clock;

    public ManageTeamsUseCase(TeamRepository teams, PlayerRepository players, Clock clock) {
        this.teams = teams;
        this.players = players;
        this.clock = clock;
    }

    public Mono<TeamResult> create(Actor actor, String name, String city) {
        return Mono.fromSupplier(() -> Team.create(actor.id(), name, city, clock.instant()))
            .flatMap(teams::save)
            .flatMap(team -> present(actor, team));
    }

    public Flux<TeamResult> listMine(Actor actor) {
        return teams.findMine(actor.id()).concatMap(team -> present(actor, team));
    }

    public Mono<TeamResult> get(Actor actor, UUID teamId) {
        return viewable(actor, teamId).flatMap(v -> present(actor, v.team(), v.players()));
    }

    public Mono<TeamResult> update(Actor actor, UUID teamId, String name, String city, int expectedVersion) {
        return editable(actor, teamId).flatMap(v -> {
            if (v.team().getVersion() != expectedVersion) {
                return Mono.<Team>error(new CricketDomainException(CricketErrorCode.OPTIMISTIC_LOCKING_CONFLICT, "This team was changed since you opened it. Reload and try again."));
            }
            v.team().update(name, city, clock.instant());
            return teams.save(v.team());
        }).flatMap(team -> present(actor, team));
    }

    public Mono<TeamResult> archive(Actor actor, UUID teamId) {
        return change(actor, teamId, team -> team.archive(clock.instant()));
    }

    public Mono<TeamResult> addMember(Actor actor, UUID teamId, UUID playerId) {
        return players.findById(playerId).filter(p -> p.usableBy(actor.id()))
            .switchIfEmpty(Mono.error(new CricketDomainException(CricketErrorCode.PLAYER_NOT_FOUND, "Player not found: " + playerId)))
            .then(Mono.defer(() -> change(actor, teamId, team -> team.addMember(playerId, clock.instant()))));
    }

    public Mono<TeamResult> removeMember(Actor actor, UUID teamId, UUID playerId) {
        return change(actor, teamId, team -> team.removeMember(playerId, clock.instant()));
    }

    public Mono<TeamResult> setCaptain(Actor actor, UUID teamId, UUID playerId) {
        return change(actor, teamId, team -> team.setCaptain(playerId, clock.instant()));
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private record Loaded(Team team, Map<UUID, Player> players) {
        boolean canEdit(UUID user) {
            return team.getCreatedBy().equals(user)
                || team.captainId().map(players::get).map(p -> user.equals(p.getCustomerId())).orElse(false);
        }

        boolean canView(UUID user) {
            return canEdit(user) || team.getMembers().stream().map(m -> players.get(m.playerId())).anyMatch(p -> p != null && user.equals(p.getCustomerId()));
        }
    }

    private Mono<Loaded> load(UUID teamId) {
        return teams.findById(teamId)
            .switchIfEmpty(Mono.error(new CricketDomainException(CricketErrorCode.TEAM_NOT_FOUND, "Team not found: " + teamId)))
            .flatMap(team -> players.findAllById(team.getMembers().stream().map(Team.Member::playerId).collect(Collectors.toSet()))
                .collectMap(Player::getId, Function.identity()).map(byId -> new Loaded(team, byId)));
    }

    private Mono<Loaded> viewable(Actor actor, UUID teamId) {
        return load(teamId).filter(v -> v.canView(actor.id()))
            .switchIfEmpty(Mono.error(new CricketDomainException(CricketErrorCode.TEAM_NOT_FOUND, "Team not found: " + teamId)));
    }

    private Mono<Loaded> editable(Actor actor, UUID teamId) {
        return viewable(actor, teamId).flatMap(v -> v.canEdit(actor.id())
            ? Mono.just(v)
            : Mono.<Loaded>error(new CricketDomainException(CricketErrorCode.NOT_ALLOWED, "Only the team's creator or captain can change it")));
    }

    private Mono<TeamResult> change(Actor actor, UUID teamId, java.util.function.Consumer<Team> mutation) {
        return editable(actor, teamId).flatMap(v -> {
            mutation.accept(v.team());
            return teams.save(v.team());
        }).flatMap(team -> present(actor, team));
    }

    private Mono<TeamResult> present(Actor actor, Team team) {
        return players.findAllById(team.getMembers().stream().map(Team.Member::playerId).collect(Collectors.toSet()))
            .collectMap(Player::getId, Function.identity()).flatMap(byId -> present(actor, team, byId));
    }

    private Mono<TeamResult> present(Actor actor, Team team, Map<UUID, Player> byId) {
        var loaded = new Loaded(team, byId);
        List<TeamResult.Member> members = team.getMembers().stream().map(m -> {
            var p = byId.get(m.playerId());
            var d = p.getDetails();
            return new TeamResult.Member(p.getId(), d.name(), d.role(), d.battingHand(), m.captain(), p.isLinked(), p.phoneVisibleTo(actor.id()) ? d.phone() : null);
        }).sorted((a, b) -> a.captain() == b.captain() ? a.name().compareToIgnoreCase(b.name()) : (a.captain() ? -1 : 1)).toList();
        return Mono.just(new TeamResult(team.getId(), team.getName(), team.getCity(), team.getStatus().name(), team.getVersion(), loaded.canEdit(actor.id()), members));
    }
}
