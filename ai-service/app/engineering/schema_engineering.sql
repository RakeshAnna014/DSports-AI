-- Engineering AI (Phase 4): a small, local, vendor-free log store in the AI database. Rows are REDACTED before they are stored, so neither a
-- query nor an LLM can ever see a secret or personal data. Idempotent (CREATE ... IF NOT EXISTS), like the other AI-owned tables.
CREATE TABLE IF NOT EXISTS eng_logs (
    id             BIGSERIAL PRIMARY KEY,
    ts             TIMESTAMPTZ NOT NULL,
    service        TEXT        NOT NULL,                 -- backend | ai-service | postgres | tika | frontend ...
    origin         TEXT        NOT NULL,                 -- real | synthetic : a synthetic record is never presented as real
    source         TEXT        NOT NULL,                 -- file / container / generator the record came from
    level          TEXT        NOT NULL,                 -- DEBUG | INFO | WARN | ERROR
    logger         TEXT,
    message        TEXT        NOT NULL,                 -- redacted, length-limited
    exception      TEXT,                                 -- redacted stack trace
    correlation_id TEXT,
    trace_id       TEXT,
    http_status    INTEGER,
    signature      TEXT,                                 -- normalised error signature (grouping key) for WARN/ERROR
    signature_text TEXT,
    raw_hash       TEXT        NOT NULL,                 -- dedupe key: re-ingesting the same file adds nothing
    CONSTRAINT uq_eng_logs_hash UNIQUE (raw_hash)
);
ALTER TABLE eng_logs ADD COLUMN IF NOT EXISTS category TEXT;                 -- DATABASE | TIMEOUT | AUTH | LLM | DOWNSTREAM | VALIDATION | OTHER (classified from message AND stack trace)
CREATE INDEX IF NOT EXISTS ix_eng_logs_ts ON eng_logs (ts DESC);
CREATE INDEX IF NOT EXISTS ix_eng_logs_service_ts ON eng_logs (service, ts DESC);
CREATE INDEX IF NOT EXISTS ix_eng_logs_corr ON eng_logs (correlation_id) WHERE correlation_id IS NOT NULL AND correlation_id <> '';
CREATE INDEX IF NOT EXISTS ix_eng_logs_signature ON eng_logs (signature, ts DESC) WHERE signature IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_eng_logs_level_ts ON eng_logs (level, ts DESC);

-- Deployments are recorded explicitly (there is no CI/CD in the repo to read them from): `python -m app.engineering.cli deploy ...`.
CREATE TABLE IF NOT EXISTS eng_deployments (
    id        BIGSERIAL PRIMARY KEY,
    ts        TIMESTAMPTZ NOT NULL,
    service   TEXT        NOT NULL,
    version   TEXT        NOT NULL,
    origin    TEXT        NOT NULL DEFAULT 'real',
    note      TEXT
);
CREATE INDEX IF NOT EXISTS ix_eng_deployments_ts ON eng_deployments (ts DESC);

-- Every investigation and every denied attempt is audited (who, what was asked, how many records were used). No log content is stored here.
CREATE TABLE IF NOT EXISTS eng_audit (
    id             BIGSERIAL PRIMARY KEY,
    ts             TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_hash     TEXT        NOT NULL,
    action         TEXT        NOT NULL,
    detail         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    correlation_id TEXT
);

-- Where an incident group stands. An "incident" is a derived group of log records sharing one error signature (there is no incident registry or ticket system
-- behind it); this table only remembers that someone ran an investigation on it, when, and by whom (hashed). It never stores log text.
CREATE TABLE IF NOT EXISTS eng_incident_status (
    signature       TEXT        NOT NULL,
    origin          TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'INVESTIGATED',
    investigated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_hash      TEXT        NOT NULL,
    note            TEXT,
    PRIMARY KEY (signature, origin)
);
