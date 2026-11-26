package com.dsports.cricket.domain.rules;

import java.util.Objects;

/**
 * The outcome of one ball, as the scorer sees it. The engine works out who was on strike, who bowled and which over it was.
 *
 * <p>{@code runs} means different things by kind: runs off the bat or byes or leg byes for a normal ball and a no-ball (see {@code runsType}),
 * and extra runs run on a wide. The wide or no-ball penalty is added on top by the engine. A {@code boundary} four or six is counted as scored,
 * not run, so it does not swap the batters.
 */
public record Delivery(Kind kind, int runs, RunsType runsType, boolean boundary, Dismissal dismissal) {

    public enum Kind { NORMAL, WIDE, NO_BALL }

    public enum RunsType { BAT, BYE, LEG_BYE }

    public Delivery {
        Objects.requireNonNull(kind, "kind");
        runsType = kind == Kind.WIDE ? null : (runsType == null ? RunsType.BAT : runsType);
    }

    public static Delivery dot() {
        return new Delivery(Kind.NORMAL, 0, RunsType.BAT, false, null);
    }

    public static Delivery runs(int runs) {
        return new Delivery(Kind.NORMAL, runs, RunsType.BAT, false, null);
    }

    public static Delivery boundary(int runs) {
        return new Delivery(Kind.NORMAL, runs, RunsType.BAT, true, null);
    }

    public static Delivery bye(int runs) {
        return new Delivery(Kind.NORMAL, runs, RunsType.BYE, false, null);
    }

    public static Delivery legBye(int runs) {
        return new Delivery(Kind.NORMAL, runs, RunsType.LEG_BYE, false, null);
    }

    /** A wide with {@code runs} extra runs run on top of the penalty. */
    public static Delivery wide(int runs) {
        return new Delivery(Kind.WIDE, runs, null, false, null);
    }

    /** A no-ball with {@code batRuns} off the bat. */
    public static Delivery noBall(int batRuns) {
        return new Delivery(Kind.NO_BALL, batRuns, RunsType.BAT, false, null);
    }

    public static Delivery noBallBoundary(int batRuns) {
        return new Delivery(Kind.NO_BALL, batRuns, RunsType.BAT, true, null);
    }

    public static Delivery noBall(int runs, RunsType type) {
        return new Delivery(Kind.NO_BALL, runs, type, false, null);
    }

    public Delivery out(Dismissal dismissal) {
        return new Delivery(kind, runs, runsType, boundary, dismissal);
    }

    public Delivery withRuns(int newRuns) {
        return new Delivery(kind, newRuns, runsType, boundary, dismissal);
    }

    boolean isLegal() {
        return kind == Kind.NORMAL;
    }
}
