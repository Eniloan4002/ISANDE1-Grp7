CREATE TABLE revoked_tokens (
 token_id uuid PRIMARY KEY,
 user_id bigint NOT NULL REFERENCES users(id),
 expires_at timestamptz NOT NULL,
 revoked_at timestamptz NOT NULL DEFAULT current_timestamp
);
CREATE INDEX revoked_tokens_expiry ON revoked_tokens(expires_at);
-- No raw JWTs, plaintext passwords or signing secrets are persisted.
-- Expired revocations may be purged: DELETE FROM revoked_tokens WHERE expires_at < now();
