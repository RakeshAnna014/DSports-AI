package com.dsports.cricket.application.usecase;

import com.dsports.cricket.application.port.CustomerDirectoryPort;
import com.dsports.cricket.application.port.MatchRepository;
import com.dsports.cricket.application.port.PlayerRepository;
import com.dsports.cricket.application.port.ScoringStore;
import com.dsports.cricket.application.port.TeamRepository;
import com.dsports.cricket.application.result.EngineState;
import com.dsports.cricket.application.result.ScoringView;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.Match;
import com.dsports.cricket.domain.model.MatchStatus;
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
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
        return scoreable(actor, matchId).flatMap(match -> store.entries(matchId).collectList().zipWith(store.snapshot(matchId).map(Optional::of).defaultIfEmpty(Optional.empty()))
            .flatMap(t -> view(actor.id(), match, t.getT1(), windowOf(match, t.getT1(), t.getT2()))));
    }

    /**
     * Everything a phone needs to score offline: the match's setup and the effective log (commands, undos and unlocks, with their seqs), with the engine version and
     * the fingerprint of the state those entries give. The same people may read it as may read the scoring view. Another scorer's events carry no client event id.
     */
    public Mono<EngineState> engineState(Actor actor, UUID matchId) {
        return scoreable(actor, matchId).flatMap(match -> store.entries(matchId).collectList().map(entries -> EngineState.of(match.getId(), MatchSetup.from(match), entries, actor.id())));
    }

    /**
     * Writes the snapshot again from the log alone (the score, the status and the correction window). A lost snapshot never opens a locked match, because every
     * request derives the window from the log when there is no snapshot; this makes that derived snapshot durable.
     */
    public Mono<Void> rebuildSnapshot(UUID matchId) {
        return matches.findById(matchId).switchIfEmpty(Mono.error(notFound(matchId))).flatMap(match -> store.exclusive(matchId, session -> session.entries().collectList().flatMap(entries -> {
            if (entries.isEmpty()) {
                return Mono.<Void>empty();
            }
            var setup = MatchSetup.from(match);
            var score = MatchScoring.fold(setup, ScoringLog.commands(entries));
            return session.saveSnapshot(new ScoringStore.Snapshot(nextSeq(entries) - 1, score, ScoringLog.windowOf(setup, entries)), score.status());
        })));
    }

    // ---- scoring ------------------------------------------------------------------------------------------------

    public Mono<ScoringView.Outcome> submit(Actor actor, UUID matchId, UUID clientEventId, int expectedSeq, ScoreCommand command) {
        return submit(actor, matchId, clientEventId, expectedSeq, command, null);
    }

    public Mono<ScoringView.Outcome> undo(Actor actor, UUID matchId, UUID clientEventId, int expectedSeq, ScoringLog.UndoMode mode) {
        return undo(actor, matchId, clientEventId, expectedSeq, mode, null);
    }

    /**
     * Applies one command made from the view at {@code expectedSeq}. {@code clientEventId} makes a retry harmless. {@code tappedAt} (optional) is when the scorer
     * says they tapped an offline ball: it is kept for the timeline only and nothing here reads it (it is not in the request fingerprint, and the 24-hour window
     * and the ordering use the server's own time and the seq).
     */
    public Mono<ScoringView.Outcome> submit(Actor actor, UUID matchId, UUID clientEventId, int expectedSeq, ScoreCommand command, Instant tappedAt) {
        var hash = fingerprint(command.type().name(), command.toString());
        return scoreable(actor, matchId).flatMap(ignored -> store.exclusive(matchId, session -> session.findByClientEventId(clientEventId)
            .map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())
            .flatMap(existing -> existing.isPresent()
                ? replay(actor, matchId, existing.get(), hash, session)
                : append(actor, matchId, clientEventId, expectedSeq, hash, session, (match, entries, window) -> {
                    var setup = MatchSetup.from(match);
                    if (command.type() != ScoreCommand.Type.END_MATCH && ScoringLog.effective(entries).isEmpty() && !match.readyToStart()) {
                        return Mono.error(new CricketDomainException(CricketErrorCode.MATCH_NOT_READY, "Record the toss and both playing XIs before scoring"));
                    }
                    var score = MatchScoring.fold(setup, ScoringLog.commands(entries));
                    return switch (MatchScoring.apply(setup, score, command)) {
                        case ScoreOutcome.Rejected r -> Mono.error(new CricketDomainException(CricketErrorCode.SCORING_REJECTED, r.message(), new ScoringView.Rejection(r.violation().name())));
                        case ScoreOutcome.Accepted a -> Mono.just(new Change(LogEntry.command(nextSeq(entries), command, clientEventId, actor.id(), clock.instant(), a.waivers()).withTappedAt(tappedAt), a.score()));
                    };
                }))));
    }

    /** Undoes the latest command ({@code COMMAND}) or the latest ball and what was chosen after it ({@code BALL}). */
    public Mono<ScoringView.Outcome> undo(Actor actor, UUID matchId, UUID clientEventId, int expectedSeq, ScoringLog.UndoMode mode, Instant tappedAt) {
        var hash = fingerprint("UNDO", String.valueOf(mode));
        return scoreable(actor, matchId).flatMap(ignored -> store.exclusive(matchId, session -> session.findByClientEventId(clientEventId)
            .map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())
            .flatMap(existing -> existing.isPresent()
                ? replay(actor, matchId, existing.get(), hash, session)
                : append(actor, matchId, clientEventId, expectedSeq, hash, session, (match, entries, window) -> {
                    var voids = ScoringLog.planUndo(entries, mode);
                    if (voids.isEmpty()) {
                        return Mono.error(new CricketDomainException(CricketErrorCode.NOTHING_TO_UNDO, "There is nothing to undo"));
                    }
                    var voided = new HashSet<>(voids);
                    var remaining = ScoringLog.effective(entries).stream().filter(e -> !voided.contains(e.seq())).map(LogEntry::command).toList();
                    return Mono.just(new Change(LogEntry.undo(nextSeq(entries), voids, clientEventId, actor.id(), clock.instant()).withTappedAt(tappedAt), MatchScoring.fold(MatchSetup.from(match), remaining)));
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
        return matches.findById(matchId).switchIfEmpty(Mono.error(notFound(matchId))).flatMap(match -> store.exclusive(matchId, session -> session.findByClientEventId(clientEventId)
            .map(Optional::of).defaultIfEmpty(Optional.empty())
            .flatMap(existing -> {
                if (existing.isPresent()) {
                    return replay(actor, matchId, existing.get(), hash, session);
                }
                return session.snapshot().map(Optional::of).defaultIfEmpty(Optional.empty()).zipWith(session.entries().collectList()).flatMap(t -> {
                    var entries = t.getT2();
                    var window = windowOf(match, entries, t.getT1());
                    var now = clock.instant();
                    if (!window.isLocked(now)) {
                        return Mono.<ScoringView.Outcome>error(new CricketDomainException(CricketErrorCode.NOT_LOCKED, "This match is not locked"));
                    }
                    int seq = nextSeq(entries);
                    var entry = LogEntry.unlock(seq, trimmed, clientEventId, actor.id(), now);
                    var score = t.getT1().map(ScoringStore.Snapshot::score).orElseGet(() -> MatchScoring.fold(MatchSetup.from(match), ScoringLog.commands(entries)));
                    var unlocked = window.unlocked(now);
                    return session.append(entry, hash, new ScoringStore.Snapshot(seq, score, unlocked), score.status())
                        .then(Mono.defer(() -> matches.findById(matchId))).flatMap(updated -> {
                            var all = new java.util.ArrayList<>(entries);
                            all.add(entry);
                            return view(actor.id(), updated, all, unlocked).map(v -> new ScoringView.Outcome(v, false, seq));
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
        return session.entries().collectList().zipWith(session.snapshot().map(Optional::of).defaultIfEmpty(Optional.empty())).flatMap(t -> {
            var entries = t.getT1();
            int latest = entries.isEmpty() ? 0 : entries.get(entries.size() - 1).seq();
            return matches.findById(matchId).switchIfEmpty(Mono.error(notFound(matchId))).flatMap(match -> {
                var window = windowOf(match, entries, t.getT2());
                if (expectedSeq != latest) {
                    return conflict(actor.id(), match, entries, window, expectedSeq);
                }
                var now = clock.instant();
                if (window.isLocked(now)) {
                    return Mono.<ScoringView.Outcome>error(new CricketDomainException(CricketErrorCode.MATCH_LOCKED, lockedMessage(MatchScoring.fold(MatchSetup.from(match), ScoringLog.commands(entries)).status())));
                }
                return decide.decide(match, entries, window).flatMap(change -> {
                    var entry = change.entry();
                    var newWindow = window.after(change.score(), entry.at());
                    var status = change.score().status();
                    return session.append(entry, hash, new ScoringStore.Snapshot(entry.seq(), change.score(), newWindow), status)
                        .then(Mono.defer(() -> {
                            var all = new java.util.ArrayList<>(entries);
                            all.add(entry);
                            return view(actor.id(), match, all, newWindow).map(v -> new ScoringView.Outcome(v, false, entry.seq()));
                        }));
                });
            });
        });
    }

    /** A command with this client event id is already in the log. The same request is answered as done; a different one is a reused id. */
    private Mono<ScoringView.Outcome> replay(Actor actor, UUID matchId, ScoringStore.Stored existing, String hash, ScoringStore.Session session) {
        if (!existing.requestHash().equals(hash)) {
            return Mono.error(new CricketDomainException(CricketErrorCode.CLIENT_EVENT_REUSED, "That client event id was already used for a different request"));
        }
        return session.entries().collectList().zipWith(session.snapshot().map(Optional::of).defaultIfEmpty(Optional.empty()))
            .zipWith(matches.findById(matchId).switchIfEmpty(Mono.error(notFound(matchId))))
            .flatMap(t -> view(actor.id(), t.getT2(), t.getT1().getT1(), windowOf(t.getT2(), t.getT1().getT1(), t.getT1().getT2())).map(v -> new ScoringView.Outcome(v, true, existing.entry().seq())));
    }

    private Mono<ScoringView.Outcome> conflict(UUID viewer, Match match, List<LogEntry> entries, EditWindow window, int expectedSeq) {
        return view(viewer, match, entries, window).zipWith(names(match, entries)).flatMap(t -> Mono.<ScoringView.Outcome>error(new CricketDomainException(CricketErrorCode.SCORE_CONFLICT,
            "Someone else has scored since you last looked. Reload the match.",
            new ScoringView.Conflict(t.getT1().seq(), t.getT1(), ScoringViewBuilder.eventsAfter(entries, expectedSeq, t.getT2(), viewer)))));
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

    /** The stored window, or, when the snapshot is missing, the one the log itself says (so a lost snapshot can never reopen a locked match). */
    private static EditWindow windowOf(Match match, List<LogEntry> entries, Optional<ScoringStore.Snapshot> snapshot) {
        if (snapshot.isPresent()) {
            return snapshot.get().window();
        }
        return entries.isEmpty() ? EditWindow.open() : ScoringLog.windowOf(MatchSetup.from(match), entries);
    }

    /** What a refused change says: a finished match, or a correction that was still in progress when the 24 hours ran out. */
    static String lockedMessage(MatchStatus status) {
        var finished = status == MatchStatus.COMPLETED || status == MatchStatus.ABANDONED || status == MatchStatus.NO_RESULT;
        return finished
            ? "This match was finished more than 24 hours ago and is locked. An administrator can unlock it."
            : "The correction window closed while the match was in progress. An administrator can unlock it.";
    }

    private Mono<ScoringViewBuilder.Names> names(Match match, List<LogEntry> entries) {
        var actorIds = entries.stream().map(LogEntry::actorId).collect(Collectors.toSet());
        return directory.describe(actorIds).flatMap(people -> NameLookup.forMatch(match, teams, players, id -> people.containsKey(id) ? people.get(id).name() : "A scorer"));
    }

    private Mono<ScoringView> view(UUID viewer, Match match, List<LogEntry> entries, EditWindow window) {
        return names(match, entries).map(names -> ScoringViewBuilder.build(match.getId(), MatchSetup.from(match), names, entries, window, clock.instant(), viewer));
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
