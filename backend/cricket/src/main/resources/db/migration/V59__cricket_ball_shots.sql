-- Shot direction (step 3e): where a scoring ball went, recorded by the scorer after the ball and only if they want to (off by default).
--
-- It is its OWN append-only table and not a column of ball_events, because the scorer taps the field after the ball has been sent, and the log cannot be edited. A
-- row says "the ball with this seq in this match went to (x, y)": x and y are in thousandths of the field's radius from the middle of the pitch (right and up positive,
-- the square 0..1000 around the centre), so a row says nothing about a screen size. It is display data only: no rule, no score, no snapshot, no fingerprint reads it, so the
-- engine vectors and the offline phone are untouched. A shot for a ball that is later undone simply stops being drawn (the analytics replay only the balls in force).
-- One shot per ball, never changed: the primary key refuses a second, and the same triggers as the log refuse UPDATE, DELETE and TRUNCATE.
--
-- There is deliberately no foreign key to ball_events: a table that references it would make PostgreSQL refuse TRUNCATE ball_events with its own message before the
-- append-only guard of V56 could fire, changing a guard that must stay exactly as it is. An insert trigger checks that the ball exists instead.
--
-- Plain CREATE TABLE plus its own guards: no existing table is altered, so the guards on ball_events are untouched.
CREATE TABLE ball_shots (
    match_id   UUID        NOT NULL REFERENCES matches (id),
    seq        INTEGER     NOT NULL,
    x          SMALLINT    NOT NULL,
    y          SMALLINT    NOT NULL,
    actor_id   UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (match_id, seq),
    CONSTRAINT ck_ball_shots_inside_field CHECK (x BETWEEN -1000 AND 1000 AND y BETWEEN -1000 AND 1000 AND (x::int * x::int + y::int * y::int) <= 1000000)
);

CREATE FUNCTION refuse_ball_shot_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ball_shots is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ball_shots_append_only
    BEFORE UPDATE OR DELETE ON ball_shots
    FOR EACH ROW EXECUTE FUNCTION refuse_ball_shot_change();

CREATE TRIGGER trg_ball_shots_no_truncate
    BEFORE TRUNCATE ON ball_shots
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_ball_shot_change();

CREATE FUNCTION require_ball_for_shot() RETURNS trigger AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ball_events WHERE match_id = NEW.match_id AND seq = NEW.seq) THEN
        RAISE EXCEPTION 'ball_shots: there is no ball % in match %', NEW.seq, NEW.match_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ball_shots_ball_exists
    BEFORE INSERT ON ball_shots
    FOR EACH ROW EXECUTE FUNCTION require_ball_for_shot();
