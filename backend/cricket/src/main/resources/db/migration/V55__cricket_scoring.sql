-- Cricket scoring (step 2d): an append-only log of scoring events and one snapshot per match.
--
-- ball_events is the source of truth. A row is never changed or removed (a trigger refuses it); an UNDO is a new row that voids earlier ones.
-- Two unique keys protect a match from two scorers: (match_id, seq) turns a stale write into an error instead of a corrupted score, and
-- (match_id, client_event_id) makes a retry harmless. match_snapshots is the read model, written in the same transaction as the event, and its seq
-- always equals the highest seq in ball_events for the match.

CREATE TABLE ball_events (
    id              UUID PRIMARY KEY,
    match_id        UUID         NOT NULL REFERENCES matches (id),
    seq             INTEGER      NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    payload         JSONB        NOT NULL,
    schema_version  SMALLINT     NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    client_event_id UUID         NOT NULL,
    actor_id        UUID         NOT NULL,
    waivers         JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_ball_events_seq CHECK (seq >= 1),
    CONSTRAINT ck_ball_events_type CHECK (type IN ('START_INNINGS', 'DELIVER', 'SELECT_BATTER', 'SELECT_BOWLER', 'CHANGE_BOWLER', 'RETIRE_BATTER',
                                                   'REVISE_TARGET', 'END_INNINGS', 'END_MATCH', 'UNDO', 'ADMIN_UNLOCK')),
    CONSTRAINT uq_ball_events_seq UNIQUE (match_id, seq),
    CONSTRAINT uq_ball_events_client UNIQUE (match_id, client_event_id)
);

CREATE FUNCTION refuse_ball_event_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ball_events is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ball_events_append_only
    BEFORE UPDATE OR DELETE ON ball_events
    FOR EACH ROW EXECUTE FUNCTION refuse_ball_event_change();

CREATE TABLE match_snapshots (
    match_id          UUID PRIMARY KEY REFERENCES matches (id),
    seq               INTEGER      NOT NULL,
    match_status      VARCHAR(20)  NOT NULL,
    score             JSONB        NOT NULL,
    result            JSONB,
    first_finished_at TIMESTAMPTZ,
    unlocked_until    TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_match_snapshots_seq CHECK (seq >= 1),
    CONSTRAINT ck_match_snapshots_status CHECK (match_status IN ('TOSS_DONE', 'IN_PROGRESS', 'INNINGS_BREAK', 'COMPLETED', 'ABANDONED', 'NO_RESULT'))
);
