package com.dsports.cricket.application.usecase;

import com.dsports.cricket.application.port.BookingLookupPort;
import com.dsports.cricket.application.port.CustomerDirectoryPort;
import com.dsports.cricket.application.port.MatchRepository;
import com.dsports.cricket.application.port.PlayerRepository;
import com.dsports.cricket.application.port.TeamRepository;
import com.dsports.cricket.application.result.MatchResult;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.Match;
import com.dsports.cricket.domain.model.Player;
import com.dsports.cricket.domain.model.Team;
import com.dsports.cricket.domain.model.TeamStatus;
import com.dsports.cricket.domain.model.TossDecision;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Match setup. The creator picks two of the teams they can see (as creator, or through a linked player), the settings and optionally a confirmed venue
 * booking of their own for cricket; the creator or an assigned scorer records the toss and the playing XIs; only the creator changes the settings and the
 * scorers. A match is visible to the creator, its scorers and people with a team in it; everyone else gets "not found". Setup is locked once the match starts.
 */
public class ManageMatchesUseCase {

    private static final String CRICKET = "cricket";

    private final MatchRepository matches;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final BookingLookupPort bookings;
    private final CustomerDirectoryPort directory;
    private final Clock clock;

    public ManageMatchesUseCase(MatchRepository matches, TeamRepository teams, PlayerRepository players, BookingLookupPort bookings, CustomerDirectoryPort directory, Clock clock) {
        this.matches = matches;
        this.teams = teams;
        this.players = players;
        this.bookings = bookings;
        this.directory = directory;
        this.clock = clock;
    }

    public record CreateCommand(UUID teamAId, UUID teamBId, Match.Settings settings, UUID bookingId) {
    }

    public Mono<MatchResult> create(Actor actor, CreateCommand command) {
        return teams.findMine(actor.id()).collectMap(Team::getId).flatMap(visible -> {
            var a = visible.get(command.teamAId());
            var b = visible.get(command.teamBId());
            if (a == null || b == null) {
                return Mono.<Match>error(new CricketDomainException(CricketErrorCode.TEAM_NOT_FOUND, "Choose two of your own teams (or teams you play in)"));
            }
            if (a.getStatus() == TeamStatus.ARCHIVED || b.getStatus() == TeamStatus.ARCHIVED) {
                return Mono.<Match>error(new CricketDomainException(CricketErrorCode.TEAM_ARCHIVED, "An archived team cannot play a new match"));
            }
            return venueFor(actor, command.bookingId())
                .map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())
                .flatMap(venue -> {
                    var settings = command.settings();
                    if (settings != null && settings.scheduledAt() == null && venue.isPresent() && venue.get().startAt() != null) {
                        settings = new Match.Settings(settings.overs(), settings.ballType(), settings.rules(), venue.get().startAt(), settings.city(), settings.umpires());
                    }
                    var match = Match.create(actor.id(), a.getId(), b.getId(), settings, venue.map(v -> v.venue()).orElse(null), clock.instant());
                    return matches.save(match);
                });
        }).flatMap(match -> present(actor, match));
    }

    public Flux<MatchResult> listMine(Actor actor) {
        return matches.findMine(actor.id()).concatMap(match -> present(actor, match));
    }

    public Mono<MatchResult> get(Actor actor, UUID matchId) {
        return viewable(actor, matchId).flatMap(match -> present(actor, match));
    }

    public Mono<MatchResult> updateSettings(Actor actor, UUID matchId, Match.Settings settings, int expectedVersion) {
        return manageable(actor, matchId).flatMap(match -> {
            if (match.getVersion() != expectedVersion) {
                return Mono.<Match>error(new CricketDomainException(CricketErrorCode.OPTIMISTIC_LOCKING_CONFLICT, "This match was changed since you opened it. Reload and try again."));
            }
            match.updateSettings(settings, clock.instant());
            return matches.save(match);
        }).flatMap(match -> present(actor, match));
    }

    public Mono<MatchResult> recordToss(Actor actor, UUID matchId, UUID winnerTeamId, TossDecision decision) {
        return change(actor, matchId, true, match -> match.recordToss(winnerTeamId, decision, clock.instant()));
    }

    /** Sets one side's playing XI in batting order. Every player must be a member of that team. */
    public Mono<MatchResult> setPlayingXi(Actor actor, UUID matchId, UUID teamId, List<UUID> playerIds) {
        return scoreable(actor, matchId).flatMap(match -> teams.findById(teamId)
            .switchIfEmpty(Mono.error(new CricketDomainException(CricketErrorCode.INVALID_TEAMS, "That team is not playing in this match")))
            .flatMap(team -> {
                match.setPlayingXi(teamId, playerIds == null ? List.of() : playerIds,
                    team.getMembers().stream().map(Team.Member::playerId).collect(Collectors.toSet()), clock.instant());
                return matches.save(match);
            })).flatMap(match -> present(actor, match));
    }

    /** Lets a registered user (found by email) score the match. Only the creator can. */
    public Mono<MatchResult> addScorer(Actor actor, UUID matchId, String email) {
        if (email == null || email.isBlank() || email.length() > 254) {
            return Mono.error(new CricketDomainException(CricketErrorCode.VALIDATION_ERROR, "An email address is required"));
        }
        return manageable(actor, matchId).flatMap(match -> directory.findIdByEmail(email.trim())
            .switchIfEmpty(Mono.error(new CricketDomainException(CricketErrorCode.USER_NOT_REGISTERED, "No registered user has that email. Ask them to register first.")))
            .flatMap(userId -> {
                match.addScorer(userId, clock.instant());
                return matches.save(match);
            })).flatMap(match -> present(actor, match));
    }

    public Mono<MatchResult> removeScorer(Actor actor, UUID matchId, UUID userId) {
        return change(actor, matchId, false, match -> match.removeScorer(userId, clock.instant()));
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private record VenueLookup(Match.Venue venue, java.time.Instant startAt) {
    }

    /** A confirmed cricket booking of the caller's own, not yet used by another match. */
    private Mono<VenueLookup> venueFor(Actor actor, UUID bookingId) {
        if (bookingId == null) {
            return Mono.empty();
        }
        return bookings.find(bookingId)
            .filter(b -> b.userId().equals(actor.id()))
            .switchIfEmpty(Mono.error(new CricketDomainException(CricketErrorCode.BOOKING_NOT_USABLE, "Booking not found: " + bookingId)))
            .flatMap(b -> {
                if (!"CONFIRMED".equals(b.status())) {
                    return Mono.<VenueLookup>error(new CricketDomainException(CricketErrorCode.BOOKING_NOT_USABLE, "Only a paid, confirmed booking can host a match"));
                }
                if (!CRICKET.equals(b.sportSlug())) {
                    return Mono.<VenueLookup>error(new CricketDomainException(CricketErrorCode.BOOKING_NOT_USABLE, "That booking is not for cricket"));
                }
                return Mono.just(new VenueLookup(new Match.Venue(b.id(), b.venueName(), b.city()), b.startAt()));
            });
    }

    private Mono<Match> viewable(Actor actor, UUID matchId) {
        return matches.findById(matchId)
            .switchIfEmpty(Mono.error(new CricketDomainException(CricketErrorCode.MATCH_NOT_FOUND, "Match not found: " + matchId)))
            .flatMap(match -> {
                if (match.isScorer(actor.id())) {
                    return Mono.just(match);
                }
                return teams.findMine(actor.id()).map(Team::getId).collect(Collectors.toSet())
                    .filter(mine -> mine.contains(match.getTeamAId()) || mine.contains(match.getTeamBId()))
                    .map(mine -> match)
                    .switchIfEmpty(Mono.error(new CricketDomainException(CricketErrorCode.MATCH_NOT_FOUND, "Match not found: " + matchId)));
            });
    }

    private Mono<Match> scoreable(Actor actor, UUID matchId) {
        return viewable(actor, matchId).flatMap(match -> match.isScorer(actor.id())
            ? Mono.just(match)
            : Mono.<Match>error(new CricketDomainException(CricketErrorCode.NOT_ALLOWED, "Only the match's creator or scorer can do that")));
    }

    private Mono<Match> manageable(Actor actor, UUID matchId) {
        return viewable(actor, matchId).flatMap(match -> match.getCreatedBy().equals(actor.id())
            ? Mono.just(match)
            : Mono.<Match>error(new CricketDomainException(CricketErrorCode.NOT_ALLOWED, "Only the match's creator can do that")));
    }

    private Mono<MatchResult> change(Actor actor, UUID matchId, boolean scorerMayDoIt, Consumer<Match> mutation) {
        return (scorerMayDoIt ? scoreable(actor, matchId) : manageable(actor, matchId)).flatMap(match -> {
            mutation.accept(match);
            return matches.save(match);
        }).flatMap(match -> present(actor, match));
    }

    private Mono<MatchResult> present(Actor actor, Match match) {
        var teamIds = Set.of(match.getTeamAId(), match.getTeamBId());
        var playerIds = new HashSet<UUID>();
        playerIds.addAll(match.playingXi(match.getTeamAId()));
        playerIds.addAll(match.playingXi(match.getTeamBId()));
        boolean canScore = match.isScorer(actor.id());
        var scorerIds = new HashSet<>(match.getScorers());
        scorerIds.add(match.getCreatedBy());
        return Flux.fromIterable(teamIds).concatMap(teams::findById).collectMap(Team::getId, Function.identity()).flatMap(teamById -> {
            if (canScore) {
                teamById.values().forEach(t -> t.getMembers().forEach(m -> playerIds.add(m.playerId())));
            }
            return Mono.zip(Mono.just(teamById),
                players.findAllById(playerIds).collectMap(Player::getId, Function.identity()),
                canScore ? directory.describe(scorerIds) : Mono.just(Map.<UUID, CustomerDirectoryPort.Person>of()))
            .map(t -> {
                var teams2 = t.getT1();
                var byId = t.getT2();
                var people = t.getT3();
                var scorers = canScore ? scorerIds.stream().filter(id -> !id.equals(match.getCreatedBy()))
                    .map(id -> new MatchResult.Scorer(id, people.containsKey(id) ? people.get(id).name() : null, people.containsKey(id) ? people.get(id).email() : null)).toList()
                    : List.<MatchResult.Scorer>of();
                var settings = match.getSettings();
                var venue = match.getVenue() == null ? null : new MatchResult.VenueInfo(match.getVenue().bookingId(), match.getVenue().name(), match.getVenue().city());
                var toss = match.getTossWinnerTeamId() == null ? null : new MatchResult.Toss(match.getTossWinnerTeamId(), match.getTossDecision());
                return new MatchResult(match.getId(), match.getStatus().name(), match.getVersion(), side(match, match.getTeamAId(), teams2, byId, canScore), side(match, match.getTeamBId(), teams2, byId, canScore),
                    settings.overs(), settings.ballType(), settings.rules(), settings.scheduledAt(), settings.city(), venue, settings.umpires(), toss, match.battingFirstTeamId(),
                    match.readyToStart(), match.getCreatedBy().equals(actor.id()), canScore, scorers);
            });
        });
    }

    private static MatchResult.Side side(Match match, UUID teamId, Map<UUID, Team> teamById, Map<UUID, Player> byId, boolean withSquad) {
        var xi = match.playingXi(teamId);
        var selected = new java.util.ArrayList<MatchResult.Selected>();
        for (int i = 0; i < xi.size(); i++) {
            var p = byId.get(xi.get(i));
            selected.add(new MatchResult.Selected(xi.get(i), p == null ? null : p.getName(), i + 1));
        }
        var squad = !withSquad ? List.<MatchResult.Selected>of() : teamById.get(teamId).getMembers().stream()
            .map(m -> new MatchResult.Selected(m.playerId(), byId.get(m.playerId()) == null ? null : byId.get(m.playerId()).getName(), 0))
            .sorted(java.util.Comparator.comparing(s -> String.valueOf(s.name()).toLowerCase())).toList();
        return new MatchResult.Side(teamId, teamById.get(teamId).getName(), selected, squad);
    }
}
