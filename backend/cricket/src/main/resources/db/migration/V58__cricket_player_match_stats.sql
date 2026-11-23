-- Career statistics (step 3b): what each player did in each COMPLETED match.
--
-- This table is a PROJECTION, not a source. ball_events (append-only) stays the only truth; a row here is the pure function CareerStats.ofMatch over the
-- replayed log, written in the same transaction as the event that finished (or un-finished, by an undo inside the correction window) the match. Because the
-- rows can always be recomputed from the log, there is deliberately NO append-only trigger here: an undo replaces a match's rows, and the rebuild job deletes
-- and rewrites them. A career line is the sum of a player's rows for one ball type, added in code (CareerStats.total), so the projection holds no
-- aggregate that could disagree with the log. Tennis-ball and leather-ball rows are kept apart by ball_type.
--
-- Plain CREATE TABLE: no existing table is altered, so the append-only triggers on ball_events (UPDATE, DELETE, TRUNCATE) are untouched.
CREATE TABLE player_match_stats (
    match_id        UUID        NOT NULL REFERENCES matches (id),
    player_id       UUID        NOT NULL REFERENCES players (id),
    ball_type       VARCHAR(10) NOT NULL,
    innings_batted  SMALLINT    NOT NULL,
    runs            INTEGER     NOT NULL,
    balls           INTEGER     NOT NULL,
    not_outs        SMALLINT    NOT NULL,
    fours           INTEGER     NOT NULL,
    sixes           INTEGER     NOT NULL,
    highest         INTEGER     NOT NULL,
    highest_not_out BOOLEAN     NOT NULL,
    fifties         SMALLINT    NOT NULL,
    hundreds        SMALLINT    NOT NULL,
    ducks           SMALLINT    NOT NULL,
    innings_bowled  SMALLINT    NOT NULL,
    balls_bowled    INTEGER     NOT NULL,
    runs_conceded   INTEGER     NOT NULL,
    wickets         INTEGER     NOT NULL,
    maidens         INTEGER     NOT NULL,
    best_wickets    INTEGER     NOT NULL,
    best_runs       INTEGER     NOT NULL,
    catches         INTEGER     NOT NULL,
    stumpings       INTEGER     NOT NULL,
    run_outs        INTEGER     NOT NULL,
    PRIMARY KEY (match_id, player_id),
    CONSTRAINT ck_pms_ball_type CHECK (ball_type IN ('LEATHER', 'TENNIS')),
    CONSTRAINT ck_pms_nonnegative CHECK (innings_batted >= 0 AND runs >= 0 AND balls >= 0 AND not_outs >= 0 AND balls_bowled >= 0 AND runs_conceded >= 0 AND wickets >= 0)
);

CREATE INDEX ix_pms_player ON player_match_stats (player_id, ball_type);
