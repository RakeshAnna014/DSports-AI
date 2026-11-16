-- Append-only audit of role grants and removals made through the administration screens (who changed whose role, when).
-- No foreign keys on purpose: the record must outlive the people it describes, and the target's email is kept as it was at the time.
CREATE TABLE role_audit_log (
    id            UUID PRIMARY KEY,
    actor_id      UUID         NOT NULL,
    target_id     UUID         NOT NULL,
    target_email  VARCHAR(254) NOT NULL,
    role          VARCHAR(30)  NOT NULL,
    action        VARCHAR(10)  NOT NULL,
    occurred_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_role_audit_action CHECK (action IN ('GRANTED', 'REVOKED'))
);

CREATE INDEX ix_role_audit_role_time ON role_audit_log (role, occurred_at DESC);
CREATE INDEX ix_role_audit_target ON role_audit_log (target_id, occurred_at DESC);
