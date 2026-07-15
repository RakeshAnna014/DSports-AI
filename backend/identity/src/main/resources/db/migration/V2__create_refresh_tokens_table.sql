CREATE TABLE refresh_tokens (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES customers(id),
    token       TEXT         NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_refresh_tokens_token ON refresh_tokens(token);
CREATE INDEX ix_refresh_tokens_user_id ON refresh_tokens(user_id);
