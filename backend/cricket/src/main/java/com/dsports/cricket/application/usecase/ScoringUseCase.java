package com.dsports.cricket.application.usecase;

import com.dsports.cricket.application.port.CustomerDirectoryPort;
import com.dsports.cricket.application.port.MatchRepository;
import com.dsports.cricket.application.port.PlayerRepository;
import com.dsports.cricket.application.port.ScoringStore;
import com.dsports.cricket.application.port.TeamRepository;
import com.dsports.cricket.application.result.ScoringView;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.Match;
import com.dsports.cricket.domain.model.Player;
import com.dsports.cricket.domain.model.Team;
import com.dsports.cricket.domain.scoring.EditWindow;
import com.dsports.cricket.domain.scoring.LogEntry;
import com.dsports.cricket.domain.scoring.MatchScoring;
import com.dsports.cricket.domain.scoring.MatchSetup;
import com.dsports.cricket.domain.scoring.ScoreCommand;
import com.dsports.cricket.domain.scoring.ScoreOutcome;
import com.dsports.cricket.domain.scoring.ScoringLog;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Scoring a match, safely for more than one scorer. Every change runs in one transaction that holds the match's lock, and goes through the same steps:
 * a retry of a command already in the log answers with that command (idempotency comes first, so a lost response is never a false conflict); a request
 * made from an old view ({@code expectedSeq} is not the latest) is a conflict; a match whose correction window has closed is locked; then the engine decides.
 * Only an accepted command is written, together with the snapshot and the match status. Undo is an event that voids earlier ones.
 */
public class ScoringUseCase {

    private final MatchRepository matches;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final CustomerDirectoryPort directory;
    private final ScoringStore store;
    private final Clock clock;

    public ScoringUseCase(MatchRepository matches, TeamRepository teams, PlayerRepository players, CustomerDirectoryPort directory, ScoringStore store, Clock clock) {
        this.matches = matches;
        this.teams = teams;
        this.players = players;
        this.directory = directory;
        this.store = store;
        this.clock = clock;
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    public Mono<ScoringView> state(Actor actor, UUID matchId) {
        return scoreable(actor, matchId).flatMap(match -> store.entries(matchId).collectList().zipWith(window(matchId))
            .flatMap(t -> view(match, t.getT1(), t.getT2())));
    }

    // ---- scoring ------------------------------------------------------------------------------------------------

    /** Applies one command made from the view at {@code expectedSeq}. {@code clientEventId} makes a retry harmless. */
    public Mono<ScoringView.Outcome> submit(Actor actor, UUID matchId, UUID clientEventId, int expectedSeq, ScoreCommand command) {
        var hash = fingerprint(command.type().name(), command.toString());
        return scoreable(actor, matchId).flatMap(ignored -> store.exclusive(matchId, session -> session.findByClientEventId(clientEventId)
            .map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())
            .flatMap(existing -> existing.isPresent()
                ? replay(matchId, existing.get(), hash, session)
                : append(actor, matchId, clientEventId, expectedSeq, hash, session, (match, entries, window) -> {
                    var setup = MatchSetup.from(match);
                    if (command.type() != ScoreCommand.Type.END_MATCH && ScoringLog.effective(entries).isEmpty() && !match.readyToStart()) {
                        return Mono.error(new CricketDomainException(CricketErrorCode.MATCH_NOT_READY, "Record the toss and both playing XIs before scoring"));
                    }
                    var score = MatchScoring.fold(setup, ScoringLog.commands(entries));
                    return switch (MatchScoring.apply(setup, score, command)) {
                        case ScoreOutcome.Rejected r -> Mono.error(new CricketDomainException(CricketErrorCode.SCORING_REJECTED, r.message(), new ScoringView.Rejection(r.violation().name())));
                        case ScoreOutcome.Accepted a -> Mono.just(new Change(LogEntry.command(nextSeq(entries), command, clientEventId, actor.id(), clock.instant(), a.waivers()), a.score()));
                    };
                }))));
    }

    /** Undoes the latest command ({@code COMMAND}) or the latest ball and what was chosen after it ({@code BALL}). */
    public Mono<ScoringView.Outcome> undo(Actor actor, UUID matchId, UUID clientEventId, int expectedSeq, ScoringLog.UndoMode mode) {
        var hash = fingerprint("UNDO", String.valueOf(mode));
        return scoreable(actor, matchId).flatMap(ignored -> store.exclusive(matchId, session -> session.findByClientEventId(clientEventId)
            .map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())
            .flatMap(existing -> existing.isPresent()
                ? replay(matchId, existing.get(), hash, session)
                : append(actor, matchId, clientEventId, expectedSeq, hash, session, (match, entries, window) -> {
                    var voids = ScoringLog.planUndo(entries, mode);
                    if (voids.isEmpty()) {
                        return Mono.error(new CricketDomainException(CricketErrorCode.NOTHING_TO_UNDO, "There is nothing to undo"));
                    }
                    var voided = new HashSet<>(voids);
                    var remaining = ScoringLog.effective(entries).stream().filter(e -> !voided.contains(e.seq())).map(LogEntry::command).toList();
                    return Mono.just(new Change(LogEntry.undo(nextSeq(entries), voids, clientEventId, actor.id(), clock.instant()), MatchScoring.fold(MatchSetup.from(match), remaining)));
                }))));
    }

    /** An administrator reopens a locked match for 24 hours. The event records who did it and why. */
    public Mono<ScoringView.Outcome> unlock(Actor actor, UUID matchId, UUID clientEventId, String reason) {
        if (!actor.admin()) {
            return Mono.error(new CricketDomainException(CricketErrorCode.NOT_ALLOWED, "Only an administrator can unlock a match"));
        }
        var trimmed = reason == null ? "" : reason.trim();
        if (trimmed.length() < 10 || trimmed.length() > 300) {
            return Mono.error(new CricketDomainException(CricketErrorCode.VALIDATION_ERROR, "Give a reason of 10 to 300 characters"));
        }
        var hash = fingerprint("ADMIN_UNLOCK", trimmed);
        return matches.findById(matchId).switchIfEmpty(Mono.error(notFound(matchId))).flatMap(ignored -> store.exclusive(matchId, session -> session.findByClientEventId(clientEventId)
            .map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())
            .flatMap(existing -> {
                if (existing.isPresent()) {
                    return replay(matchId, existing.get(), hash, session);
                }
                return session.snapshot().map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty()).zipWith(session.entries().collectList()).flatMap(t -> {
                    var window = t.getT1().map(ScoringStore.Snapshot::window).orElse(EditWindow.open());
                    var now = clock.instant();
                    if (!window.isLocked(now)) {
                        return Mono.<ScoringView.Outcome>error(new CricketDomainException(CricketErrorCode.NOT_LOCKED, "This match is not locked"));
                    }
                    var entries = t.getT2();
                    int seq = nextSeq(entries);
                    var entry = LogEntry.unlock(seq, trimmed, clientEventId, actor.id(), now);
                    var snapshot = t.getT1().orElseThrow();
                    var unlocked = window.unlocked(now);
                    return session.append(entry, hash, new ScoringStore.Snapshot(seq, snapshot.score(), unlocked), snapshot.score().status())
                        .then(Mono.defer(() -> matches.findById(matchId))).flatMap(match -> {
                            var all = new java.util.ArrayList<>(entries);
                            all.add(entry);
                            return view(match, all, unlocked).map(v -> new ScoringView.Outcome(v, false, seq));
                        });
                });
            })));
    }

    // ---- the shared steps ---------------------------------------------------------------------------------------

    /** What a command decided: the log entry to add and the match score after it. */
    private record Change(LogEntry entry, com.dsports.cricket.domain.scoring.MatchScore score) {
    }

    @FunctionalInterface
    private interface Decide {
        Mono<Change> decide(Match match, List<LogEntry> entries, EditWindow window);
    }

    private Mono<ScoringView.Outcome> append(Actor actor, UUID matchId, UUID clientEventId, int expectedSeq, String hash, ScoringStore.Session session, Decide decide) {
        return session.entries().collectList().zipWith(session.snapshot().map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())).flatMap(t -> {
            var entries = t.getT1();
            int latest = entries.isEmpty() ? 0 : entries.get(entries.size() - 1).seq();
            var window = t.getT2().map(ScoringStore.Snapshot::window).orElse(EditWindow.open());
            return matches.findById(matchId).switchIfEmpty(Mono.error(notFound(matchId))).flatMap(match -> {
                if (expectedSeq != latest) {
                    return conflict(match, entries, window, expectedSeq);
                }
                var now = clock.instant();
                if (window.isLocked(now)) {
                    return Mono.<ScoringView.Outcome>error(new CricketDomainException(CricketErrorCode.MATCH_LOCKED,
                        "This match was finished more than 24 hours ago and is locked. An administrator can unlock it."));
                }
                return decide.decide(match, entries, window).flatMap(change -> {
                    var entry = change.entry();
                    var newWindow = window.after(change.score(), now);
                    var status = change.score().status();
                    return session.append(entry, hash, new ScoringStore.Snapshot(entry.seq(), change.score(), newWindow), status)
                        .then(Mono.defer(() -> {
                            var all = new java.util.ArrayList<>(entries);
                            all.add(entry);
                            return view(match, all, newWindow).map(v -> new ScoringView.Outcome(v, false, entry.seq()));
                        }));
                });
            });
        });
    }

    /** A command with this client event id is already in the log. The same request is answered as done; a different one is a reused id. */
    private Mono<ScoringView.Outcome> replay(UUID matchId, ScoringStore.Stored existing, String hash, ScoringStore.Session session) {
        if (!existing.requestHash().equals(hash)) {
            return Mono.error(new CricketDomainException(CricketErrorCode.CLIENT_EVENT_REUSED, "That client event id was already used for a different request"));
        }
        return session.entries().collectList().zipWith(session.snapshot().map(ScoringStore.Snapshot::window).defaultIfEmpty(EditWindow.open()))
            .zipWith(matches.findById(matchId).switchIfEmpty(Mono.error(notFound(matchId))))
            .flatMap(t -> view(t.getT2(), t.getT1().getT1(), t.getT1().getT2()).map(v -> new ScoringView.Outcome(v, true, existing.entry().seq())));
    }

    private Mono<ScoringView.Outcome> conflict(Match match, List<LogEntry> entries, EditWindow window, int expectedSeq) {
        return view(match, entries, window).zipWith(names(match, entries)).flatMap(t -> Mono.<ScoringView.Outcome>error(new CricketDomainException(CricketErrorCode.SCORE_CONFLICT,
            "Someone else has scored since you last looked. Reload the match.",
            new ScoringView.Conflict(t.getT1().seq(), t.getT1(), ScoringViewBuilder.eventsAfter(entries, expectedSeq, t.getT2())))));
    }

    // ---- access and views ---------------------------------------------------------------------------------------

    private static CricketDomainException notFound(UUID matchId) {
        return new CricketDomainException(CricketErrorCode.MATCH_NOT_FOUND, "Match not found: " + matchId);
    }

    /** The creator or a scorer. Someone with only a team in the match is told they may not (403); anyone else is told the match does not exist (404). */
    private Mono<Match> scoreable(Actor actor, UUID matchId) {
        return matches.findById(matchId).switchIfEmpty(Mono.error(notFound(matchId))).flatMap(match -> {
            if (match.isScorer(actor.id())) {
                return Mono.just(match);
            }
            return teams.findMine(actor.id()).map(Team::getId).collect(Collectors.toSet())
                .filter(mine -> mine.contains(match.getTeamAId()) || mine.contains(match.getTeamBId()))
                .flatMap(mine -> Mono.<Match>error(new CricketDomainException(CricketErrorCode.NOT_ALLOWED, "Only the match's creator or scorer can do that")))
                .switchIfEmpty(Mono.error(notFound(matchId)));
        });
    }

    private Mono<EditWindow> window(UUID matchId) {
        return store.snapshot(matchId).map(ScoringStore.Snapshot::window).defaultIfEmpty(EditWindow.open());
    }

    private Mono<ScoringViewBuilder.Names> names(Match match, List<LogEntry> entries) {
        var playerIds = new HashSet<UUID>();
        playerIds.addAll(match.playingXi(match.getTeamAId()));
        playerIds.addAll(match.playingXi(match.getTeamBId()));
        var actorIds = entries.stream().map(LogEntry::actorId).collect(Collectors.toSet());
        return Mono.zip(players.findAllById(playerIds).collectMap(Player::getId, Player::getName),
                teams.findById(match.getTeamAId()).map(Team::getName).defaultIfEmpty("Team A"),
                teams.findById(match.getTeamBId()).map(Team::getName).defaultIfEmpty("Team B"),
                directory.describe(actorIds))
            .map(t -> {
                Map<UUID, String> playerNames = t.getT1();
                Map<UUID, CustomerDirectoryPort.Person> people = t.getT4();
                return new ScoringViewBuilder.Names(id -> playerNames.getOrDefault(id, "Player"),
                    id -> id.equals(match.getTeamAId()) ? t.getT2() : t.getT3(),
                    id -> people.containsKey(id) ? people.get(id).name() : "A scorer");
            });
    }

    private Mono<ScoringView> view(Match match, List<LogEntry> entries, EditWindow window) {
        return names(match, entries).map(names -> ScoringViewBuilder.build(match.getId(), MatchSetup.from(match), names, entries, window, clock.instant()));
    }

    private static int nextSeq(List<LogEntry> entries) {
        return entries.isEmpty() ? 1 : entries.get(entries.size() - 1).seq() + 1;
    }

    /** A fingerprint of a request: the same request always gives the same one, so a retry can be told from a reused id. */
    static String fingerprint(String type, String content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest((type + "|" + content).getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
