package com.dsports.cricket.domain.rules;

import java.util.UUID;

/**
 * A dismissal on a ball. {@code batterOut} may be null for the kinds only a striker can suffer (the striker is meant). {@code end} is only for a run out:
 * the end of the pitch at which the batter was run out, because that decides who is left where and so who is on strike next. {@code fielderId} is optional
 * and only for caught, run out and stumped: a player of the fielding side (the catcher, the one who ran the batter out, the wicket-keeper).
 */
public record Dismissal(Type type, UUID batterOut, End end, UUID fielderId) {

    public enum Type { BOWLED, CAUGHT, LBW, STUMPED, HIT_WICKET, RUN_OUT, OBSTRUCTING_FIELD, HIT_BALL_TWICE }

    /** BATTING_END is the end the bowler bowls to, where the striker stands. */
    public enum End { BATTING_END, BOWLERS_END }

    public Dismissal(Type type, UUID batterOut, End end) {
        this(type, batterOut, end, null);
    }

    /** A dismissal of the striker, such as bowled or caught. */
    public static Dismissal striker(Type type) {
        return new Dismissal(type, null, null);
    }

    public static Dismissal of(Type type, UUID batter) {
        return new Dismissal(type, batter, null);
    }

    public static Dismissal runOut(UUID batter, End end) {
        return new Dismissal(Type.RUN_OUT, batter, end);
    }

    public Dismissal by(UUID fielder) {
        return new Dismissal(type, batterOut, end, fielder);
    }
}
