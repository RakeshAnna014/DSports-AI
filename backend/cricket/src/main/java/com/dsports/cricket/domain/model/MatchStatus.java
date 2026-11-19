package com.dsports.cricket.domain.model;

public enum MatchStatus {
    SCHEDULED, TOSS_DONE, IN_PROGRESS, INNINGS_BREAK, COMPLETED, ABANDONED, NO_RESULT;

    /** Being played right now: the match is in an innings or between the two. This is what the public "live matches" list shows. */
    public boolean isLive() {
        return this == IN_PROGRESS || this == INNINGS_BREAK;
    }
}
