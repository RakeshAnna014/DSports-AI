package com.dsports.cricket.domain.scoring;

import com.dsports.cricket.domain.model.MatchStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * The rules of the event log. Undo is an event, not a deletion: the <em>effective</em> log is the commands that no later UNDO voids, and the match state
 * is the fold of that. So an undo needs no special case for overs, wickets or innings: it is the same fold with fewer commands.
 */
public final class ScoringLog {

    /** COMMAND takes off the latest command. BALL takes off the latest ball and whatever was chosen after it (the new batter, the new bowler). */
    public enum UndoMode { COMMAND, BALL }

    private ScoringLog() {
    }

    /** The commands that are still in force, in order. */
    public static List<LogEntry> effective(List<LogEntry> entries) {
        var voided = new HashSet<Integer>();
        entries.stream().filter(e -> e.kind() == LogEntry.Kind.UNDO).forEach(e -> voided.addAll(e.voids()));
        return entries.stream().filter(e -> e.kind() == LogEntry.Kind.COMMAND && !voided.contains(e.seq())).toList();
    }

    public static List<ScoreCommand> commands(List<LogEntry> entries) {
        return effective(entries).stream().map(LogEntry::command).toList();
    }

    /**
     * The seqs an undo in this mode would void, latest first, or empty when there is nothing to undo. BALL mode takes the choices that follow a ball
     * and then the one command they followed, and never goes further, so it cannot silently cross the start of an innings.
     */
    public static List<Integer> planUndo(List<LogEntry> entries, UndoMode mode) {
        var live = effective(entries);
        var voids = new ArrayList<Integer>();
        for (int i = live.size() - 1; i >= 0; i--) {
            var entry = live.get(i);
            voids.add(entry.seq());
            if (mode == UndoMode.COMMAND || !entry.command().isFollower()) {
                break;
            }
        }
        return voids;
    }

    /**
     * The correction window as the log alone says it: the clock starts at the first entry after which the match was finished (its time is the entry's own
     * time), is never cleared, and the last admin unlock grants 24 hours from that unlock. This is what the stored snapshot holds, so a lost snapshot can be
     * rebuilt exactly.
     */
    public static EditWindow windowOf(MatchSetup setup, List<LogEntry> entries) {
        var window = EditWindow.open();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            if (entry.kind() == LogEntry.Kind.ADMIN_UNLOCK) {
                window = window.unlocked(entry.at());
            } else if (window.firstFinishedAt() == null) {
                window = window.after(MatchScoring.fold(setup, commands(entries.subList(0, i + 1))), entry.at());
            }
        }
        return window;
    }

    /** True when undoing would reopen something that was closed: an innings, the innings break, a completed or ended match. */
    public static boolean reopens(MatchScore before, MatchScore after) {
        if (after.innings().size() < before.innings().size()) {
            return true;
        }
        var was = before.status();
        return was != after.status() && (was == MatchStatus.INNINGS_BREAK || was == MatchStatus.COMPLETED || was == MatchStatus.ABANDONED || was == MatchStatus.NO_RESULT);
    }
}
