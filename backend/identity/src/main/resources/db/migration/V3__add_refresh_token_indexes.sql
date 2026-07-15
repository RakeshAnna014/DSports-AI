CREATE INDEX ix_refresh_tokens_expires_at ON refresh_tokens(expires_at);
CREATE INDEX ix_refresh_tokens_user_id_active ON refresh_tokens(user_id) WHERE revoked = FALSE;
