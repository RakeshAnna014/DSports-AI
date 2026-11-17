package com.dsports.cricket.infrastructure.persistence;

import com.dsports.cricket.application.port.MatchRepository;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.BallType;
import com.dsports.cricket.domain.model.Match;
import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.model.MatchStatus;
import com.dsports.cricket.domain.model.TossDecision;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * The match row is saved with optimistic locking (every change, including a toss, XI or scorer change, bumps its version); then the playing XIs and the
 * scorers are replaced. The use case runs this in one transaction, and a stale writer fails on the match row before any child row is touched.
 */
public class MatchR2dbcRepositoryAdapter implements MatchRepository {

    private final SpringR2dbcMatchRepository repository;
    private final DatabaseClient db;

    public MatchR2dbcRepositoryAdapter(SpringR2dbcMatchRepository repository, DatabaseClient db) {
        this.repository = repository;
        this.db = db;
    }

    @Override
    public Mono<Match> findById(UUID id) {
        return repository.findById(id).flatMap(this::withChildren);
    }

    @Override
    public Flux<Match> findMine(UUID user) {
        return db.sql("""
                SELECT m.id FROM matches m
                 WHERE m.created_by = :user
                    OR EXISTS (SELECT 1 FROM match_scorers s WHERE s.match_id = m.id AND s.user_id = :user)
                    OR m.team_a_id IN (SELECT team_id FROM (%1$s) mine) OR m.team_b_id IN (SELECT team_id FROM (%1$s) mine)
                 ORDER BY m.created_at DESC
                """.formatted("""
                SELECT t.id AS team_id FROM teams t
                 WHERE t.created_by = :user
                    OR EXISTS (SELECT 1 FROM team_members tm JOIN players p ON p.id = tm.player_id WHERE tm.team_id = t.id AND p.customer_id = :user)
                """))
            .bind("user", user).map((row, meta) -> row.get("id", UUID.class)).all()
            .concatMap(id -> repository.findById(id).flatMap(this::withChildren));
    }

    @Override
    public Mono<Match> save(Match match) {
        return repository.save(toEntity(match))
            .doOnNext(saved -> match.setVersion(saved.getVersion()))
            .then(Mono.defer(() -> replaceChildren(match)))
            .thenReturn(match)
            .onErrorMap(OptimisticLockingFailureException.class, e -> new CricketDomainException(CricketErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                "Match " + match.getId() + " was modified concurrently. Reload and retry."))
            .onErrorMap(DataIntegrityViolationException.class, e -> String.valueOf(e.getMessage()).contains("uq_matches_booking")
                ? new CricketDomainException(CricketErrorCode.BOOKING_ALREADY_USED, "That booking already hosts another match")
                : new CricketDomainException(CricketErrorCode.VALIDATION_ERROR, "The match could not be saved"));
    }

    private Mono<Void> replaceChildren(Match match) {
        var deleteXi = db.sql("DELETE FROM match_players WHERE match_id = :match").bind("match", match.getId()).fetch().rowsUpdated();
        var deleteScorers = db.sql("DELETE FROM match_scorers WHERE match_id = :match").bind("match", match.getId()).fetch().rowsUpdated();
        return deleteXi.then(deleteScorers)
            .thenMany(Flux.just(match.getTeamAId(), match.getTeamBId()).concatMap(team -> {
                var xi = match.playingXi(team);
                return Flux.range(0, xi.size()).concatMap(i -> db.sql("INSERT INTO match_players (match_id, team_id, player_id, batting_order) VALUES (:match, :team, :player, :order)")
                    .bind("match", match.getId()).bind("team", team).bind("player", xi.get(i)).bind("order", i + 1).fetch().rowsUpdated());
            }))
            .thenMany(Flux.fromIterable(match.getScorers()).concatMap(user -> db.sql("INSERT INTO match_scorers (match_id, user_id) VALUES (:match, :user)")
                .bind("match", match.getId()).bind("user", user).fetch().rowsUpdated()))
            .then();
    }

    private Mono<Match> withChildren(MatchEntity e) {
        var xi = db.sql("SELECT team_id, player_id FROM match_players WHERE match_id = :match ORDER BY batting_order")
            .bind("match", e.getId()).map((row, meta) -> new UUID[] {row.get("team_id", UUID.class), row.get("player_id", UUID.class)}).all().collectList();
        var scorers = db.sql("SELECT user_id FROM match_scorers WHERE match_id = :match ORDER BY added_at, user_id")
            .bind("match", e.getId()).map((row, meta) -> row.get("user_id", UUID.class)).all().collectList();
        return Mono.zip(xi, scorers).map(t -> {
            var a = t.getT1().stream().filter(p -> p[0].equals(e.getTeamAId())).map(p -> p[1]).toList();
            var b = t.getT1().stream().filter(p -> p[0].equals(e.getTeamBId())).map(p -> p[1]).toList();
            return toDomain(e, a, b, new LinkedHashSet<>(t.getT2()));
        });
    }

    static Match toDomain(MatchEntity e, List<UUID> xiA, List<UUID> xiB, java.util.Set<UUID> scorers) {
        var rules = new MatchRules(e.getBallsPerOver(), e.getPlayersPerSide(), e.getMaxOversPerBowler(), e.getWidePenalty(), e.getNoBallPenalty(), e.isFreeHitEnabled(), e.isLastManStands());
        var settings = new Match.Settings(e.getOvers(), BallType.valueOf(e.getBallType()), rules, e.getScheduledAt(), e.getCity(), e.getUmpires());
        var venue = e.getBookingId() == null ? null : new Match.Venue(e.getBookingId(), e.getVenueName(), e.getCity());
        return Match.reconstitute(e.getId(), e.getTeamAId(), e.getTeamBId(), settings, venue, MatchStatus.valueOf(e.getStatus()), e.getTossWinnerTeamId(),
            e.getTossDecision() == null ? null : TossDecision.valueOf(e.getTossDecision()), xiA, xiB, scorers, e.getCreatedBy(), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }

    static MatchEntity toEntity(Match m) {
        var s = m.getSettings();
        var r = s.rules();
        var e = new MatchEntity();
        e.setId(m.getId());
        e.setTeamAId(m.getTeamAId());
        e.setTeamBId(m.getTeamBId());
        e.setOvers(s.overs());
        e.setBallType(s.ballType().name());
        e.setBallsPerOver(r.ballsPerOver());
        e.setPlayersPerSide(r.playersPerSide());
        e.setMaxOversPerBowler(r.maxOversPerBowler());
        e.setWidePenalty(r.widePenalty());
        e.setNoBallPenalty(r.noBallPenalty());
        e.setFreeHitEnabled(r.freeHitEnabled());
        e.setLastManStands(r.lastManStands());
        e.setStatus(m.getStatus().name());
        e.setScheduledAt(s.scheduledAt());
        e.setCity(s.city());
        e.setBookingId(m.getVenue() == null ? null : m.getVenue().bookingId());
        e.setVenueName(m.getVenue() == null ? null : m.getVenue().name());
        e.setUmpires(s.umpires());
        e.setTossWinnerTeamId(m.getTossWinnerTeamId());
        e.setTossDecision(m.getTossDecision() == null ? null : m.getTossDecision().name());
        e.setCreatedBy(m.getCreatedBy());
        e.setVersion(m.getVersion());
        e.setCreatedAt(m.getCreatedAt());
        e.setUpdatedAt(m.getUpdatedAt());
        return e;
    }
}
