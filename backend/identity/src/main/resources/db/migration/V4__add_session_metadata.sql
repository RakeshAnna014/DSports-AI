ALTER TABLE refresh_tokens
    ADD COLUMN device_name   VARCHAR(200),
    ADD COLUMN user_agent    TEXT,
    ADD COLUMN ip_address    VARCHAR(45),
    ADD COLUMN last_used_at  TIMESTAMP NOT NULL DEFAULT NOW();

CREATE INDEX ix_refresh_tokens_last_used_at ON refresh_tokens(last_used_at);
