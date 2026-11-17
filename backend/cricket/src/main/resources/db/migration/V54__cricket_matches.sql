-- Cricket, step 2b (ADR-019): match setup: two teams, overs, ball type, rules, optional venue booking, toss, playing XIs and scorers.
-- Forward-only. No foreign keys to other modules' tables (the booking id is a reference with a venue snapshot, not a constraint).
-- Statuses beyond TOSS_DONE are used from step 2d on; the CHECK already lists the whole lifecycle so it never has to be widened.

CREATE TABLE matches (
    id                    UUID PRIMARY KEY,
    team_a_id             UUID         NOT NULL REFERENCES teams (id),
    team_b_id             UUID         NOT NULL REFERENCES teams (id),
    overs                 INTEGER      NOT NULL,
    ball_type             VARCHAR(10)  NOT NULL,
    balls_per_over        INTEGER      NOT NULL DEFAULT 6,
    players_per_side      INTEGER      NOT NULL DEFAULT 11,
    max_overs_per_bowler  INTEGER,
    wide_penalty          INTEGER      NOT NULL DEFAULT 1,
    no_ball_penalty       INTEGER      NOT NULL DEFAULT 1,
    free_hit_enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    last_man_stands       BOOLEAN      NOT NULL DEFAULT FALSE,
    status                VARCHAR(14)  NOT NULL DEFAULT 'SCHEDULED',
    scheduled_at          TIMESTAMP WITH TIME ZONE,
    city                  VARCHAR(80),
    booking_id            UUID,
    venue_name            VARCHAR(120),
    umpires               VARCHAR(200),
    toss_winner_team_id   UUID REFERENCES teams (id),
    toss_decision         VARCHAR(4),
    created_by            UUID         NOT NULL,
    version               INTEGER      NOT NULL DEFAULT 0,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_matches_teams CHECK (team_a_id <> team_b_id),
    CONSTRAINT ck_matches_overs CHECK (overs BETWEEN 1 AND 100),
    CONSTRAINT ck_matches_ball_type CHECK (ball_type IN ('LEATHER', 'TENNIS')),
    CONSTRAINT ck_matches_balls_per_over CHECK (balls_per_over BETWEEN 4 AND 10),
    CONSTRAINT ck_matches_players CHECK (players_per_side BETWEEN 2 AND 11),
    CONSTRAINT ck_matches_bowler_limit CHECK (max_overs_per_bowler IS NULL OR (max_overs_per_bowler >= 1 AND max_overs_per_bowler <= overs)),
    CONSTRAINT ck_matches_penalties CHECK (wide_penalty BETWEEN 0 AND 5 AND no_ball_penalty BETWEEN 0 AND 5),
    CONSTRAINT ck_matches_status CHECK (status IN ('SCHEDULED', 'TOSS_DONE', 'IN_PROGRESS', 'INNINGS_BREAK', 'COMPLETED', 'ABANDONED', 'NO_RESULT')),
    CONSTRAINT ck_matches_toss CHECK ((toss_winner_team_id IS NULL) = (toss_decision IS NULL)),
    CONSTRAINT ck_matches_toss_decision CHECK (toss_decision IS NULL OR toss_decision IN ('BAT', 'BOWL')),
    CONSTRAINT ck_matches_scheduled_no_toss CHECK (status <> 'SCHEDULED' OR toss_winner_team_id IS NULL),
    CONSTRAINT ck_matches_toss_done CHECK (status <> 'TOSS_DONE' OR toss_winner_team_id IS NOT NULL)
);

-- A confirmed venue booking can be the venue of one match only.
CREATE UNIQUE INDEX uq_matches_booking ON matches (booking_id) WHERE booking_id IS NOT NULL;
CREATE INDEX ix_matches_created_by ON matches (created_by, created_at DESC);
CREATE INDEX ix_matches_team_a ON matches (team_a_id);
CREATE INDEX ix_matches_team_b ON matches (team_b_id);
CREATE INDEX ix_matches_status_city ON matches (status, lower(city));

-- The playing XI of each side, in batting order.
CREATE TABLE match_players (
    match_id       UUID    NOT NULL REFERENCES matches (id),
    team_id        UUID    NOT NULL REFERENCES teams (id),
    player_id      UUID    NOT NULL REFERENCES players (id),
    batting_order  INTEGER NOT NULL,
    PRIMARY KEY (match_id, player_id),
    CONSTRAINT uq_match_players_order UNIQUE (match_id, team_id, batting_order),
    CONSTRAINT ck_match_players_order CHECK (batting_order BETWEEN 1 AND 11)
);

CREATE INDEX ix_match_players_player ON match_players (player_id);

-- Registered users who may score the match, besides its creator.
CREATE TABLE match_scorers (
    match_id  UUID NOT NULL REFERENCES matches (id),
    user_id   UUID NOT NULL,
    added_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (match_id, user_id)
);

CREATE INDEX ix_match_scorers_user ON match_scorers (user_id);
