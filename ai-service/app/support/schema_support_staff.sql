-- Staff-side AI assistance. Cases live in the Java `support` module; nothing about a case is copied here except its reference number.
--
-- support_ai_actions is an APPEND-ONLY log of what the assistant suggested for a case and what the staff member then did with it:
--   SUGGESTED  the plan offered the action (by itself it changes nothing)
--   ATTEMPTED  the staff member confirmed it and the console called the backend
--   SUCCEEDED  the backend answered with success (the console then re-reads the case to verify)
--   FAILED     the backend refused or failed (detail carries the HTTP status and error code, never customer content)
-- It never records that a ticket was "resolved by AI": resolution is a backend state set by an authorised staff member.
CREATE TABLE IF NOT EXISTS support_ai_actions (
    id         BIGSERIAL PRIMARY KEY,
    at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    case_ref   TEXT        NOT NULL,
    actor_hash TEXT        NOT NULL,
    action_id  TEXT        NOT NULL,
    kind       TEXT        NOT NULL,
    label      TEXT        NOT NULL,
    state      TEXT        NOT NULL CHECK (state IN ('SUGGESTED', 'ATTEMPTED', 'SUCCEEDED', 'FAILED')),
    detail     JSONB       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS ix_support_ai_actions_case ON support_ai_actions (case_ref, at);
CREATE INDEX IF NOT EXISTS ix_support_ai_actions_action ON support_ai_actions (case_ref, action_id, at);
